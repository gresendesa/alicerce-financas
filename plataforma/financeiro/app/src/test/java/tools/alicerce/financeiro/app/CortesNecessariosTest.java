package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.ConferirSaldo;
import tools.alicerce.financeiro.aplicacao.CortesNecessarios;
import tools.alicerce.financeiro.aplicacao.CortesRegistrado;
import tools.alicerce.financeiro.aplicacao.CortesVisto;
import tools.alicerce.financeiro.aplicacao.DeclaracaoRegistrada;
import tools.alicerce.financeiro.aplicacao.DeclaracaoVista;
import tools.alicerce.financeiro.aplicacao.Declarar;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.OperacaoVista;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.RetratoVisto;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;

/**
 * Testes de aceite (declarar e cortes_necessarios), nos casos de uso, contra o banco. As datas
 * saem de hoje; todo gasto cai no dia 1, para os números não dependerem do dia em que o teste roda.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class CortesNecessariosTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirCompromisso definirCompromisso;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired Declarar declarar;
    @Autowired CortesNecessarios cortes;
    @Autowired Desfazer desfazer;
    @Autowired ConferirSaldo conferirSaldo;
    @Autowired ObterContexto obterContexto;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired Vistas vistas;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;
    private YearMonth mes;

    @BeforeEach
    void dono() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:profile"), "gui");
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
        mes = YearMonth.from(hoje);
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    /**
     * Itaú com 5.000,00 desde o primeiro dia de sete meses atrás. Nos seis meses antes deste: Mercado 100,00, 200,00 ...
     * 600,00 (percentil 80 = 500,00; mediana = 350,00) e Lazer 100,00 em cada um. Neste mês, 120,00 no Mercado. Até o fim
     * do mês, um aluguel de 1.000,00 a pagar e um bônus de 300,00 a receber. Saldo de partida: 5.000 − 2.700 − 120 = 2.180,00.
     */
    private void cenario() {
        conta("corrente", "Itaú", "5000.00", mes.minusMonths(7).atDay(1).toString());
        conta("categoria", "Mercado", null, null);
        conta("categoria", "Lazer", null, null);
        conta("categoria", "Moradia", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        for (int i = 6; i >= 1; i--) {
            gasto((7 - i) + "00.00", mes.minusMonths(i).atDay(1), "Mercado");
            gasto("100.00", mes.minusMonths(i).atDay(1), "Lazer");
        }
        gasto("120.00", mes.atDay(1), "Mercado");
        compromisso("saida", "Aluguel", "1000.00", "Moradia");
        compromisso("entrada", "Bônus", "300.00", null);
    }

    @Test
    void deficitCobertoPrimeiroPelasOutrasDepoisPelasEssenciais() {
        cenario();
        declarar("categorias_essenciais", null, List.of("Mercado"));

        CortesRegistrado registrado = cortes.executar(chamador, new CortesNecessarios.Entrada(null, "1200.00", null, null));
        CortesVisto c = registrado.cortes();

        assertThat(c.fim()).isEqualTo(mes.atEndOfMonth().toString());
        assertThat(c.saldoPartida()).isEqualTo("2180.00");
        assertThat(c.entradasConhecidas()).isEqualTo("300.00");
        assertThat(c.saidasConhecidas()).isEqualTo("1000.00");
        assertThat(c.variavelRestante()).isEqualTo("480.00");
        assertThat(c.saldoProjetado()).isEqualTo("1000.00");
        assertThat(c.alvo().tipo()).isEqualTo("saldo_final");
        assertThat(c.distancia()).isEqualTo("-200.00");
        assertThat(c.deficit()).isEqualTo("200.00");
        assertThat(c.folga()).isEqualTo("0.00");
        assertThat(c.naoCortaveis()).extracting(i -> i.nome()).containsExactly("Aluguel");
        assertThat(c.entradas()).extracting(i -> i.nome()).containsExactly("Bônus");
        assertThat(c.mesesReferencia()).isEqualTo(6);

        CortesVisto.CategoriaCorte mercado = categoria(c, "Mercado");
        assertThat(mercado.essencial()).isTrue();
        assertThat(mercado.gastoAteHoje()).isEqualTo("120.00");
        assertThat(mercado.tipico()).isEqualTo("500.00");
        assertThat(mercado.restanteEsperado()).isEqualTo("380.00");
        assertThat(mercado.piso()).isEqualTo("0.00");
        assertThat(mercado.espacoCorte()).isEqualTo("380.00");

        assertThat(c.plano().cortes()).extracting(CortesVisto.Corte::nome).containsExactly("Lazer", "Mercado");
        assertThat(c.plano().cortes()).extracting(CortesVisto.Corte::corte).containsExactly("100.00", "100.00");
        assertThat(c.plano().cortes().get(1).restanteComCorte()).isEqualTo("280.00");
        assertThat(c.plano().coberto()).isEqualTo("200.00");
        assertThat(c.plano().descoberto()).isEqualTo("0.00");
        assertThat(c.plano().precisaEssenciais()).isTrue();
        // O Itaú nunca foi conferido: a conferência venceu 30 dias depois do marco.
        assertThat(c.ressalvas()).extracting(CortesVisto.Ressalva::codigo).containsExactly("conferencia_vencida");
        assertThat(c.notas()).anyMatch(n -> n.contains("cartão"));

        CortesVisto otimista = cortes.executar(chamador, new CortesNecessarios.Entrada("saldo_final", "1200.00", null, "p50")).cortes();
        assertThat(otimista.variavelRestante()).isEqualTo("330.00");
        assertThat(otimista.saldoProjetado()).isEqualTo("1150.00");
        assertThat(otimista.plano().cortes()).extracting(CortesVisto.Corte::nome).containsExactly("Lazer");
    }

    @Test
    void pisoDeclaradoComFolgaEDesfazerTrazAAnteriorDeVolta() {
        cenario();
        assertThatThrownBy(() -> cortes.executar(chamador, new CortesNecessarios.Entrada(null, null, null, null)))
                .isInstanceOfSatisfying(ErroTipado.class, e -> assertThat(e.camposFaltando()).containsExactly("saldo_final"));

        DeclaracaoRegistrada primeira = declarar("piso_periodo", "0.01", null).valor();
        assertThat(primeira.anterior()).isNull();
        assertThat(primeira.declaracao().saldoMinimo()).isEqualTo("0.01");
        assertThat(primeira.declaracao().contasPagas()).isTrue();
        ResultadoEscrita<DeclaracaoRegistrada> segunda = declarar("piso_periodo", "1500.00", null);
        assertThat(segunda.valor().anterior().saldoMinimo()).isEqualTo("0.01");

        CortesVisto c = cortes.executar(chamador, new CortesNecessarios.Entrada(null, null, null, null)).cortes();
        assertThat(c.alvo().tipo()).isEqualTo("piso_periodo");
        assertThat(c.alvo().valor()).isEqualTo("1500.00");
        assertThat(c.alvo().operacaoDeclaracao()).isEqualTo(segunda.operacao().referencia().id());
        assertThat(c.deficit()).isEqualTo("500.00");
        // Espaço total de 480,00: o plano corta tudo e deixa 20,00 sem cobertura.
        assertThat(c.plano().coberto()).isEqualTo("480.00");
        assertThat(c.plano().descoberto()).isEqualTo("20.00");

        assertThat(obterContexto.executar(chamador).declaracoes()).extracting(DeclaracaoVista::saldoMinimo).containsExactly("1500.00");
        String alvo = segunda.operacao().referencia().id();
        ReversaoRegistrada previa = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor();
        assertThat(previa.declaracoesRevertidas()).singleElement().satisfies(d -> {
            assertThat(d.tipo()).isEqualTo("piso_periodo");
            assertThat(d.sai().saldoMinimo()).isEqualTo("1500.00");
            assertThat(d.volta().saldoMinimo()).isEqualTo("0.01");
        });
        // A prévia não muda nada.
        assertThat(obterContexto.executar(chamador).declaracoes()).extracting(DeclaracaoVista::saldoMinimo).containsExactly("1500.00");
        ReversaoRegistrada aplicada = desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, previa.confirmacao())).valor();
        assertThat(aplicada.declaracoesRevertidas()).singleElement().satisfies(d -> assertThat(d.volta().saldoMinimo()).isEqualTo("0.01"));
        assertThat(obterContexto.executar(chamador).declaracoes()).extracting(DeclaracaoVista::saldoMinimo).containsExactly("0.01");

        CortesVisto verde = cortes.executar(chamador, new CortesNecessarios.Entrada(null, null, null, null)).cortes();
        assertThat(verde.folga()).isEqualTo("999.99");
        assertThat(verde.deficit()).isEqualTo("0.00");
        assertThat(verde.plano().cortes()).isEmpty();
    }

    @Test
    void comMenosDeTresMesesSaiADistanciaMasNaoSaiPlano() {
        conta("corrente", "Itaú", "300.00", mes.minusMonths(1).atDay(1).toString());
        conta("categoria", "Lazer", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        gasto("100.00", mes.minusMonths(1).atDay(1), "Lazer");

        conferirSaldo.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Itaú", null, "1234.50", null, null, null));
        CortesVisto c = cortes.executar(chamador, new CortesNecessarios.Entrada(null, "500.00", null, null)).cortes();

        assertThat(c.ressalvas()).filteredOn(r -> r.codigo().equals("diferenca_pendente")).singleElement()
                .satisfies(r -> assertThat(r.mensagem()).contains("Itaú (diferença de R$ 1.034,50)"));
        assertThat(c.ressalvas().get(0).mensagem()).contains("não sugira cortes por conta própria");
        assertThat(c.mesesReferencia()).isEqualTo(1);
        assertThat(c.saldoProjetado()).isEqualTo("100.00");
        assertThat(c.deficit()).isEqualTo("400.00");
        assertThat(c.plano()).isNull();
        assertThat(c.ressalvas()).extracting(CortesVisto.Ressalva::codigo).contains("historico_insuficiente");
        assertThat(c.ressalvas().get(0).mensagem()).contains("não há plano");
    }

    @Test
    void oRetratoGuardaOResultadoAbreNaTelaENaoApareceNoHistorico() {
        cenario();
        CortesRegistrado registrado = cortes.executar(chamador, new CortesNecessarios.Entrada(null, "1200.00", null, null));
        assertThat(registrado.retrato()).startsWith("s-");

        RetratoVisto retrato = (RetratoVisto) resolver.resolver(chamador, registrado.retrato(), true);
        assertThat(retrato.capacidade()).isEqualTo("cortes_necessarios");
        assertThat(retrato.resultado().get("saldo_projetado").asString()).isEqualTo("1000.00");
        assertThat(retrato.comparacao().mudou()).isFalse();
        gasto("10.00", hoje, "Lazer");
        RetratoVisto depois = (RetratoVisto) resolver.resolver(chamador, registrado.retrato(), true);
        assertThat(depois.comparacao().mudou()).isTrue();

        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrado.urlRetrato().substring(registrado.urlRetrato().indexOf("/fin/")),
                null, null, null, null));
        assertThat(tela.dados().toString()).contains("1000.00", "Aluguel", "Lazer", "Mercado");

        assertThat(listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, null, null, null)).operacoes())
                .extracting(OperacaoVista::capacidade).doesNotContain("cortes_necessarios");
    }

    @Test
    void entradasInvalidasVoltamComOCampo() {
        // nota é aceita: sem texto, pede o texto.
        assertThat(erro(() -> declarar("nota", null, null)).camposFaltando()).containsExactly("texto");
        assertThat(erro(() -> declarar("piso_periodo", null, null)).camposFaltando()).containsExactly("saldo_minimo");
        assertThat(erro(() -> declarar("piso_periodo", "-1.00", null)).campo()).isEqualTo("saldo_minimo");
        assertThat(erro(() -> declarar("categorias_essenciais", null, null)).camposFaltando()).containsExactly("categorias");
        assertThat(erro(() -> declarar("categorias_essenciais", null, List.of("Inexistente"))).codigo()).isEqualTo(CodigoErro.REFERENCIA_INEXISTENTE);
        assertThat(erro(() -> cortes.executar(chamador, new CortesNecessarios.Entrada("meta", null, null, null))).camposFaltando()).containsExactly("meta");
        assertThat(erro(() -> cortes.executar(chamador, new CortesNecessarios.Entrada("outro", null, null, null))).campo()).isEqualTo("alvo");
        assertThat(erro(() -> cortes.executar(chamador, new CortesNecessarios.Entrada(null, "10.00", null, "p90"))).campo()).isEqualTo("estimativa");
        assertThat(erro(() -> cortes.executar(chamador, new CortesNecessarios.Entrada(null, "10.00", mes.plusMonths(1).atDay(1).toString(), null)))
                .campo()).isEqualTo("data");
    }

    private ResultadoEscrita<DeclaracaoRegistrada> declarar(String tipo, String saldo, List<String> categorias) {
        return declarar.executar(chamador, new Declarar.Entrada(chave(), null, tipo, saldo, categorias, null));
    }

    private static ErroTipado erro(Runnable acao) {
        try {
            acao.run();
        } catch (ErroTipado e) {
            return e;
        }
        throw new AssertionError("Era esperado um erro");
    }

    private static CortesVisto.CategoriaCorte categoria(CortesVisto c, String nome) {
        return c.categorias().stream().filter(l -> nome.equals(l.nome())).findFirst().orElseThrow();
    }

    private void compromisso(String direcao, String nome, String valor, String categoria) {
        definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, direcao, "unico", nome, null, valor, null, null,
                null, null, mes.atEndOfMonth().toString(), null, null, null, categoria, "Itaú", null, null, null, null));
    }

    private void gasto(String valor, LocalDate data, String categoria) {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra", "Pix", "conversa", data.toString(), categoria,
                null, null, null, null, null, null, true, null));
    }

    private void conta(String tipo, String nome, String saldo, String data) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, data, null));
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }
}
