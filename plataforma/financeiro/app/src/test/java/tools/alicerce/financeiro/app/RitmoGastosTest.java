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
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.CompromissoVisto;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.OperacaoVista;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;
import tools.alicerce.financeiro.aplicacao.RetratoVisto;
import tools.alicerce.financeiro.aplicacao.RitmoGastos;
import tools.alicerce.financeiro.aplicacao.RitmoRegistrado;
import tools.alicerce.financeiro.aplicacao.RitmoVisto;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;

/**
 * Testes de aceite (ritmo_gastos), nos casos de uso, contra o banco. As datas saem de hoje: o mês
 * analisado é o anterior ao de hoje, e as referências são os seis antes dele.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class RitmoGastosTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirCompromisso definirCompromisso;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired ResumirPeriodo resumir;
    @Autowired RitmoGastos ritmo;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired Vistas vistas;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;
    private YearMonth analisado;

    @BeforeEach
    void dono() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:profile"), "gui");
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
        analisado = YearMonth.from(hoje).minusMonths(1);
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    /**
     * Seis meses iguais: mercado, 4 compras de 50,00 na Padaria; lazer, 100,00 no Cinema; aluguel de 1.000,00
     * pago pelo compromisso. No mês analisado, o mesmo e mais uma compra de 400,00 no Atacadão.
     */
    private void seisMesesEUmAtipico() {
        conta("corrente", "Itaú", "50000.00", analisado.minusMonths(7).atDay(1).toString());
        conta("categoria", "Mercado", null, null);
        conta("categoria", "Lazer", null, null);
        conta("categoria", "Moradia", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        YearMonth primeiro = analisado.minusMonths(6);
        CompromissoVisto aluguel = definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, "saida", "recorrente",
                "Aluguel", null, "1000.00", null, null, null, "mensal", primeiro.atDay(5).toString(), null, null, null, "Moradia", "Itaú", null,
                null, null, null)).valor();
        for (int i = 0; i <= 6; i++) {
            YearMonth mes = primeiro.plusMonths(i);
            for (int dia : new int[] {2, 9, 16, 23}) {
                gasto("50.00", mes.atDay(dia), "Mercado", "Padaria", null, null);
            }
            gasto("100.00", mes.atDay(5), "Lazer", "Cinema", null, null);
            gasto("1000.00", mes.atDay(5), "Moradia", null, aluguel.id(), String.valueOf(i + 1));
        }
        gasto("400.00", analisado.atDay(12), "Mercado", "Atacadão", null, null);
    }

    @Test
    void mesFechadoComTipicoResponsaveisEAtipicaSemOsFixos() {
        seisMesesEUmAtipico();

        RitmoRegistrado registrado = ritmo.executar(chamador, new RitmoGastos.Entrada(analisado.toString(), null, null, null));
        RitmoVisto r = registrado.ritmo();

        assertThat(r.emCurso()).isFalse();
        assertThat(r.diasDecorridos()).isEqualTo(analisado.lengthOfMonth());
        assertThat(r.projecoes()).isNull();
        assertThat(r.acumulado()).isEqualTo("700.00");
        assertThat(r.tipico().acumulado()).isEqualTo("300.00");
        assertThat(r.tipico().diferenca()).isEqualTo("400.00");
        assertThat(r.tipico().percentual()).isEqualTo("133.33");
        assertThat(r.tipico().mesesUsados()).isEqualTo(6);
        assertThat(r.mesesReferencia()).hasSize(6).allSatisfy(m -> assertThat(m.total()).isEqualTo("300.00"));
        assertThat(r.ressalvas()).isEmpty();

        assertThat(r.responsaveis().categorias()).singleElement().satisfies(c -> {
            assertThat(c.nome()).isEqualTo("Mercado");
            assertThat(c.id()).startsWith("a-");
            assertThat(c.atual()).isEqualTo("600.00");
            assertThat(c.tipico()).isEqualTo("200.00");
            assertThat(c.contribuicao()).isEqualTo("400.00");
        });
        assertThat(r.responsaveis().contrapartes()).singleElement().satisfies(c -> assertThat(c.nome()).isEqualTo("Atacadão"));
        assertThat(r.responsaveis().comprasAtipicas()).singleElement().satisfies(c -> {
            assertThat(c.valor()).isEqualTo("400.00");
            assertThat(c.percentil95()).isEqualTo("50.00");
            assertThat(c.categoria()).isEqualTo("Mercado");
            assertThat(c.url()).contains("/fin/t/");
        });

        assertThat(r.tendencia().meses()).containsExactly(analisado.minusMonths(3).toString(), analisado.minusMonths(2).toString(),
                analisado.minusMonths(1).toString());
        assertThat(r.tendencia().categorias()).extracting(RitmoVisto.TendenciaCategoria::nome).contains("Mercado", "Lazer", "Moradia");
        assertThat(r.tendencia().categorias()).allSatisfy(c -> assertThat(c.subindo()).isFalse());
        assertThat(r.tendencia().categorias()).filteredOn(c -> c.nome().equals("Mercado")).singleElement()
                .satisfies(c -> assertThat(c.variacoes()).containsExactly("0.00", "0.00"));
    }

    @Test
    void comOsFixosCadaMesBateComOResumoNaMesmaVisao() {
        seisMesesEUmAtipico();

        for (String visao : List.of("competencia", "caixa")) {
            RitmoVisto r = ritmo.executar(chamador, new RitmoGastos.Entrada(analisado.toString(), null, true, visao)).ritmo();
            assertThat(r.mesesReferencia()).hasSize(6);
            for (RitmoVisto.MesReferencia mes : r.mesesReferencia()) {
                assertThat(mes.total()).as(visao + " " + mes.mes()).isEqualTo(saidas(mes.mes(), visao));
            }
            assertThat(r.acumulado()).as(visao).isEqualTo(saidas(analisado.toString(), visao));
            assertThat(r.tipico().acumulado()).isEqualTo("1300.00");
        }
    }

    @Test
    void naVisaoCaixaAParcelaDeCompraDeMesAnteriorEhFixaEADoMesNao() {
        conta("corrente", "Itaú", "50000.00", analisado.minusMonths(7).atDay(1).toString());
        conta("categoria", "Mercado", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        // Fecha no dia 10 e vence no 20: a compra do dia 5 vence no dia 20 do mesmo mês.
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Nubank", null, null, null, null,
                analisado.minusMonths(7).atDay(1).toString(), null, new DadosCartao(10, 20, "5000.00", "Itaú")));
        // Em 2x no mês anterior ao analisado: a parcela 1 vence nele mesmo, a 2 no analisado.
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "300.00", "Geladeira", "Nubank", "conversa",
                analisado.minusMonths(1).atDay(5).toString(), "Mercado", null, null, null, null, null, null, true, null, null, 2, null, null,
                null, null, null, null));
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "80.00", "Feira", "Nubank", "conversa",
                analisado.atDay(5).toString(), "Mercado", null, null, null, null, null, null, true, null));

        RitmoVisto sem = ritmo.executar(chamador, new RitmoGastos.Entrada(analisado.toString(), null, false, "caixa")).ritmo();
        RitmoVisto com = ritmo.executar(chamador, new RitmoGastos.Entrada(analisado.toString(), null, true, "caixa")).ritmo();

        assertThat(sem.acumulado()).isEqualTo("80.00");
        assertThat(com.acumulado()).isEqualTo("230.00").isEqualTo(saidas(analisado.toString(), "caixa"));
        RitmoVisto.MesReferencia anterior = sem.mesesReferencia().get(sem.mesesReferencia().size() - 1);
        assertThat(anterior.mes()).isEqualTo(analisado.minusMonths(1).toString());
        assertThat(anterior.total()).as("a parcela 1 é de compra do próprio mês").isEqualTo("150.00");
    }

    @Test
    void semTresMesesValidosNaoInventaTipicoEDizDesdeQuando() {
        LocalDate marco = YearMonth.from(hoje).minusMonths(1).atDay(15);
        conta("corrente", "Itaú", "5000.00", marco.toString());
        conta("categoria", "Mercado", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        gasto("90.00", hoje.withDayOfMonth(1), "Mercado", "Padaria", null, null);

        RitmoVisto r = ritmo.executar(chamador, new RitmoGastos.Entrada(null, null, null, null)).ritmo();

        assertThat(r.mes()).isEqualTo(YearMonth.from(hoje).toString());
        assertThat(r.emCurso()).isTrue();
        assertThat(r.acumulado()).isEqualTo("90.00");
        assertThat(r.tipico()).isNull();
        assertThat(r.responsaveis()).isNull();
        assertThat(r.projecoes().mantidoRitmoAtual()).isNotNull();
        assertThat(r.projecoes().acumuladoMaisRestanteTipico()).isNull();
        assertThat(r.ressalvas()).extracting(RitmoVisto.Ressalva::codigo).containsExactly("historico_insuficiente", "tendencia_nao_avaliada");
        RitmoVisto.Ressalva ressalva = r.ressalvas().get(0);
        assertThat(ressalva.mesesValidos()).isZero();
        assertThat(ressalva.historicoCompletoDesde()).isEqualTo(YearMonth.from(hoje).toString());
        assertThat(ressalva.mensagem()).contains("Itaú", marco.toString(), "sem inventar um típico", "não muda o marco");
    }

    @Test
    void oRetratoGuardaOResultadoAbreNaTelaENaoApareceNoHistorico() {
        seisMesesEUmAtipico();
        long antes = operacoes();

        RitmoRegistrado registrado = ritmo.executar(chamador, new RitmoGastos.Entrada(analisado.toString(), null, null, null));

        assertThat(operacoes()).isEqualTo(antes + 1);
        assertThat(registrado.retrato()).startsWith("s-");
        RetratoVisto retrato = (RetratoVisto) resolver.resolver(chamador, registrado.retrato(), true);
        assertThat(retrato.capacidade()).isEqualTo("ritmo_gastos");
        assertThat(retrato.resultado().get("acumulado").asString()).isEqualTo("700.00");
        assertThat(retrato.parametros().get("hoje").asString()).isEqualTo(hoje.toString());
        assertThat(retrato.comparacao().mudou()).isFalse();
        assertThat(retrato.comparacao().acumulado().agora()).isEqualTo("700.00");

        gasto("10.00", analisado.atDay(3), "Lazer", null, null, null);
        RetratoVisto depois = (RetratoVisto) resolver.resolver(chamador, registrado.retrato(), true);
        assertThat(depois.comparacao().mudou()).isTrue();
        assertThat(depois.comparacao().acumulado().diferenca()).isEqualTo("10.00");

        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrado.urlRetrato().substring(registrado.urlRetrato().indexOf("/fin/")),
                null, null, null, null));
        assertThat(tela.dados().toString()).contains("700.00", "Atacadão");

        List<OperacaoVista> historico = listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, null, null, null))
                .operacoes();
        assertThat(historico).extracting(OperacaoVista::capacidade).doesNotContain("ritmo_gastos");
        List<OperacaoVista> pedidas = listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, "ritmo_gastos", null,
                null)).operacoes();
        assertThat(pedidas).singleElement().satisfies(o -> assertThat(o.resumo()).contains(registrado.retrato()));
    }

    @Test
    void entradasInvalidasVoltamComOCampo() {
        assertThatThrownBy(() -> ritmo.executar(chamador, new RitmoGastos.Entrada("novembro", null, null, null)))
                .isInstanceOfSatisfying(ErroTipado.class, e -> assertThat(e.campo()).isEqualTo("periodo"));
        assertThatThrownBy(() -> ritmo.executar(chamador, new RitmoGastos.Entrada(YearMonth.from(hoje).plusMonths(1).toString(), null, null, null)))
                .isInstanceOfSatisfying(ErroTipado.class, e -> assertThat(e.campo()).isEqualTo("periodo"));
        assertThatThrownBy(() -> ritmo.executar(chamador, new RitmoGastos.Entrada(null, 2, null, null)))
                .isInstanceOfSatisfying(ErroTipado.class, e -> assertThat(e.campo()).isEqualTo("meses_referencia"));
        assertThatThrownBy(() -> ritmo.executar(chamador, new RitmoGastos.Entrada(null, null, null, "mensal")))
                .isInstanceOfSatisfying(ErroTipado.class, e -> assertThat(e.campo()).isEqualTo("visao"));
    }

    private String saidas(String periodo, String visao) {
        ResumoVisto r = resumir.calcularAgora(chamador, new ResumirPeriodo.Entrada(null, null, periodo, List.of("categoria"), null, null, visao, false));
        return r.saidas();
    }

    private void gasto(String valor, LocalDate data, String categoria, String contraparte, String compromisso, String ocorrencia) {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra", "Pix", "conversa", data.toString(), categoria,
                null, null, null, null, contraparte, null, true, null, null, null, null, null, null, null, compromisso, ocorrencia));
    }

    private void conta(String tipo, String nome, String saldo, String data) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, data, null));
    }

    private long operacoes() {
        return banco.fetchValue("select count(*) from operacao where dono_id = ?", dono) instanceof Long n ? n : -1;
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }
}
