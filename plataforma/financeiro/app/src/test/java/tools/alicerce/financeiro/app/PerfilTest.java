package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.alicerce.financeiro.aplicacao.DeclaracaoRegistrada;
import tools.alicerce.financeiro.aplicacao.DeclaracaoVista;
import tools.alicerce.financeiro.aplicacao.Declarar;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.DefinirMeta;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.ObterSituacao;
import tools.alicerce.financeiro.aplicacao.ProjecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista.CenarioVisto;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista.MesVisto;
import tools.alicerce.financeiro.aplicacao.Projetar;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.RitmoGastos;
import tools.alicerce.financeiro.aplicacao.RitmoRegistrado;
import tools.alicerce.financeiro.aplicacao.RitmoVisto;
import tools.alicerce.financeiro.aplicacao.Situacao;
import tools.alicerce.financeiro.aplicacao.Situacao.SinalVisto;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;

/**
 * Testes de aceite: as declarações que faltavam, os sinais de {@code situacao} que dependem delas,
 * as parcelas e violações em {@code projetar} e a variabilidade e a poupança em {@code ritmo_gastos}. Nos casos de uso,
 * contra o banco; as datas saem de hoje, e todo fato do histórico cai no dia 1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class PerfilTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirCompromisso definirCompromisso;
    @Autowired DefinirMeta definirMeta;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired Declarar declarar;
    @Autowired Desfazer desfazer;
    @Autowired ObterContexto obterContexto;
    @Autowired ObterSituacao situacao;
    @Autowired Projetar projetar;
    @Autowired RitmoGastos ritmo;
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
     * Itaú com marco há 7 meses; nos 6 meses de referência, Mercado gasta 100,00 a 600,00 e Lazer 100,00: o total de cada mês
     * vai de 200,00 a 700,00, e o gasto mensal típico (a mediana) é 450,00. Com {@code renda}, cada mês fechado recebe
     * 1.000,00 de salário.
     */
    private void historico(boolean renda) {
        conta("corrente", "Itaú", "5000.00", mes.minusMonths(7).atDay(1).toString());
        conta("categoria", "Mercado", null, null);
        conta("categoria", "Lazer", null, null);
        conta("fonte_renda", "Salário", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        for (int i = 6; i >= 1; i--) {
            gasto((7 - i) + "00.00", mes.minusMonths(i).atDay(1), "Mercado", "Pix", null);
            gasto("100.00", mes.minusMonths(i).atDay(1), "Lazer", "Pix", null);
            if (renda) {
                ganho("1000.00", mes.minusMonths(i).atDay(1));
            }
        }
    }

    @Test
    void osCincoTiposNovosComValidacaoDesfazerEContexto() {
        historico(false);
        DeclaracaoRegistrada emMeses = declarar(entrada("reserva_minima", null, 3, null, null, null)).valor();
        assertThat(emMeses.anterior()).isNull();
        DeclaracaoVista reserva = emMeses.declaracao();
        assertThat(reserva.mesesDeGasto()).isEqualTo(3);
        assertThat(reserva.gastoMensalTipico()).isEqualTo("450.00");
        assertThat(reserva.valorEquivalente()).isEqualTo("1350.00");
        assertThat(reserva.mesesGastoTipico()).hasSize(6).startsWith(mes.minusMonths(6).toString()).endsWith(mes.minusMonths(1).toString());
        assertThat(reserva.valor()).isNull();
        assertThat(reserva.ressalva()).isNull();

        ResultadoEscrita<DeclaracaoRegistrada> porValor = declarar(entrada("reserva_minima", "5000.00", null, null, null, null));
        assertThat(porValor.valor().declaracao().valorEquivalente()).isEqualTo("5000.00");
        assertThat(porValor.valor().anterior().mesesDeGasto()).isEqualTo(3);
        confirmar(porValor.operacao().referencia().id());
        assertThat(vigente("reserva_minima").mesesDeGasto()).isEqualTo(3);

        assertThat(declarar(entrada("intencao_poupanca", null, null, "15", null, null)).valor().declaracao().percentual()).isEqualTo("15.00");
        assertThat(declarar(entrada("intencao_poupanca", null, null, "0", null, null)).valor().declaracao().percentual()).isEqualTo("0.00");
        assertThat(declarar(entrada("teto_parcelas", null, null, "25%", null, null)).valor().declaracao().percentual()).isEqualTo("25.00");
        String meta = definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Viagem", null, "6000.00",
                mes.plusMonths(5).atEndOfMonth().toString(), null, null, null, null)).valor().id();
        definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Sem data", null, "100.00", null, null, null, null, null));
        DeclaracaoVista protegida = declarar(entrada("meta_protegida", null, null, null, List.of("Viagem"), null)).valor().declaracao();
        assertThat(protegida.metas()).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(meta);
            assertThat(m.nome()).isEqualTo("Viagem");
            assertThat(m.situacao()).isEqualTo("ativa");
        });
        assertThat(declarar(entrada("nota", null, null, null, null, "  Prefiro não parcelar viagens.  ")).valor().declaracao().texto())
                .isEqualTo("Prefiro não parcelar viagens.");

        assertThat(obterContexto.executar(chamador).declaracoes()).extracting(DeclaracaoVista::tipo).containsExactlyInAnyOrder(
                "reserva_minima", "intencao_poupanca", "teto_parcelas", "meta_protegida", "nota");

        assertThat(erro(() -> declarar(entrada("reserva_minima", null, null, null, null, null))).camposFaltando())
                .containsExactly("valor", "meses_de_gasto");
        assertThat(erro(() -> declarar(entrada("reserva_minima", "100.00", 2, null, null, null))).campo()).isEqualTo("meses_de_gasto");
        assertThat(erro(() -> declarar(entrada("reserva_minima", null, 25, null, null, null))).campo()).isEqualTo("meses_de_gasto");
        assertThat(erro(() -> declarar(entrada("teto_parcelas", null, null, "0", null, null))).campo()).isEqualTo("percentual");
        assertThat(erro(() -> declarar(entrada("intencao_poupanca", null, null, "100.001", null, null))).campo()).isEqualTo("percentual");
        assertThat(erro(() -> declarar(entrada("intencao_poupanca", null, null, "101", null, null))).campo()).isEqualTo("percentual");
        assertThat(erro(() -> declarar(entrada("teto_parcelas", "100.00", null, "20", null, null))).mensagem())
                .contains("valor só vale em reserva_minima");
        assertThat(erro(() -> declarar(entrada("meta_protegida", null, null, null, List.of("Sem data"), null))).campo()).isEqualTo("metas[0]");
        assertThat(erro(() -> declarar(entrada("nota", null, null, null, null, "x".repeat(1001)))).campo()).isEqualTo("texto");
        assertThat(erro(() -> declarar(entrada("nota", null, null, null, null, null))).camposFaltando()).containsExactly("texto");
    }

    @Test
    void reservaEmMesesSemHistoricoFicaSemValorComRessalva() {
        conta("corrente", "Itaú", "1000.00", hoje.toString());
        DeclaracaoVista reserva = declarar(entrada("reserva_minima", null, 6, null, null, null)).valor().declaracao();
        assertThat(reserva.valorEquivalente()).isNull();
        assertThat(reserva.gastoMensalTipico()).isNull();
        assertThat(reserva.ressalva()).contains("ficou sem valor", "Não estime");
        // O mês em que o valor passa a sair vem do servidor: o histórico completo mais 3 meses.
        java.time.YearMonth completo = hoje.getDayOfMonth() == 1 ? mes : mes.plusMonths(1);
        assertThat(reserva.ressalva()).contains("passa a sair em " + completo.plusMonths(3));
        // Sem os meses fechados, o ritmo dos aportes é desconhecido: a meta não aparece como atrasada.
        definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Viagem", null, "6000.00",
                mes.plusMonths(5).atEndOfMonth().toString(), null, null, null, null));

        Situacao s = situacao.executar(chamador);
        assertThat(s.sinais()).noneMatch(x -> x.codigo().equals("meta_atrasada"));
        assertThat(s.ressalvas()).anyMatch(r -> r.startsWith("meta_atrasada não foi avaliado para Viagem"));
        assertThat(s.sinais()).noneMatch(x -> x.codigo().equals("reserva_abaixo_projetada"));
        assertThat(s.ressalvas()).anyMatch(r -> r.startsWith("reserva_abaixo_projetada não foi avaliado"));
        assertThat(s.naoAvaliados()).extracting(Situacao.NaoAvaliado::codigo).doesNotContain("reserva_abaixo_projetada");
    }

    @Test
    void semDeclaracoesOsSinaisDoPerfilSaemComoNaoAvaliados() {
        historico(true);
        Situacao s = situacao.executar(chamador);
        assertThat(s.horizonteMeses()).isEqualTo(3);
        assertThat(s.naoAvaliados()).extracting(Situacao.NaoAvaliado::codigo, Situacao.NaoAvaliado::declaracaoQueFalta).containsExactly(
                org.assertj.core.groups.Tuple.tuple("reserva_abaixo_projetada", "reserva_minima"),
                org.assertj.core.groups.Tuple.tuple("parcelas_acima_teto", "teto_parcelas"),
                org.assertj.core.groups.Tuple.tuple("poupanca_abaixo", "intencao_poupanca"));
        // Só os sinais de antes: a conta sem conferência e o Mercado subindo.
        assertThat(s.sinais()).extracting(SinalVisto::codigo).containsOnly("conferencia_antiga", "categoria_subindo");
        // "Não poupar" nunca dispara.
        declarar(entrada("intencao_poupanca", null, null, "0", null, null));
        assertThat(situacao.executar(chamador).sinais()).extracting(SinalVisto::codigo).containsOnly("conferencia_antiga", "categoria_subindo");
        assertThat(erro(() -> situacao.executar(chamador, 25)).campo()).isEqualTo("horizonte_meses");
    }

    @Test
    void situacaoVigiaReservaTetoPoupancaEMetaComOsNumeros() {
        historico(true);
        cartao("Nubank", 10, 17);
        // Saldo de hoje: 5.000,00 + 6.000,00 de salário − 2.700,00 de gastos = 8.300,00.
        compromisso("entrada", "recorrente", "Salário fixo", "1000.00", mes.plusMonths(1).atDay(5));
        gasto("1200.00", hoje, "Lazer", "Nubank", 3);
        declarar(entrada("reserva_minima", "9000.00", null, null, null, null));
        declarar(entrada("teto_parcelas", null, null, "10", null, null));
        declarar(entrada("intencao_poupanca", null, null, "50", null, null));
        String meta = definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Viagem", null, "6000.00",
                mes.plusMonths(5).atEndOfMonth().toString(), null, null, null, null)).valor().id();
        definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Carro", null, "60000.00",
                mes.plusMonths(10).atEndOfMonth().toString(), null, null, null, null));
        declarar(entrada("meta_protegida", null, null, null, List.of(meta), null));

        Situacao s = situacao.executar(chamador, 4);
        assertThat(s.horizonteMeses()).isEqualTo(4);
        assertThat(s.naoAvaliados()).isNull();

        SinalVisto reserva = sinal(s, "reserva_abaixo_projetada");
        assertThat(reserva.severidade()).isEqualTo("critico");
        assertThat(reserva.alvo()).isEqualTo("declaracao");
        assertThat(reserva.id()).startsWith("o-");
        assertThat(reserva.perfil().mes()).isEqualTo(mes.toString());
        assertThat(reserva.perfil().reservaMinima()).isEqualTo("9000.00");
        assertThat(reserva.perfil().meses()).contains(mes.toString());

        SinalVisto teto = sinal(s, "parcelas_acima_teto");
        assertThat(teto.severidade()).isEqualTo("aviso");
        assertThat(teto.perfil().teto()).isEqualTo("10.00");
        assertThat(teto.perfil().parcelas()).isEqualTo("400.00");
        assertThat(teto.perfil().renda()).isEqualTo("1000.00");
        assertThat(teto.perfil().comprometimento()).isEqualTo("40.00");

        // Meses fechados: 3.000,00 de renda e 500,00 + 600,00 + 700,00 de saídas.
        SinalVisto poupanca = sinal(s, "poupanca_abaixo");
        assertThat(poupanca.severidade()).isEqualTo("aviso");
        assertThat(poupanca.perfil().poupancaObservada()).isEqualTo("40.00");
        assertThat(poupanca.perfil().poupancaDeclarada()).isEqualTo("50.00");
        assertThat(poupanca.perfil().rendaObservada()).isEqualTo("3000.00");
        assertThat(poupanca.perfil().saidasObservadas()).isEqualTo("1800.00");

        List<SinalVisto> metas = s.sinais().stream().filter(x -> x.codigo().equals("meta_atrasada")).toList();
        assertThat(metas).extracting(SinalVisto::nome, SinalVisto::severidade).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Viagem", "critico"), org.assertj.core.groups.Tuple.tuple("Carro", "aviso"));
        assertThat(metas.get(0).perfil().protegida()).isTrue();
        assertThat(metas.get(0).perfil().ritmoMensal()).isEqualTo("0.00");
        assertThat(metas.get(0).perfil().aporteMensalNecessario()).isEqualTo("1000.00");
        assertThat(metas.get(0).perfil().mesProjetado()).isNull();
        assertThat(metas.get(0).url()).contains("/fin/m/");
        assertThat(s.contagem().critico()).isEqualTo(2);
    }

    @Test
    void projetarMostraParcelasComprometimentoEViolacoesDoCenario() {
        historico(false);
        cartao("Nubank", 10, 17);
        compromisso("entrada", "recorrente", "Salário fixo", "1000.00", mes.plusMonths(1).atDay(5));
        declarar(entrada("reserva_minima", "2000.00", null, null, null, null));
        declarar(entrada("teto_parcelas", null, null, "25", null, null));
        String meta = definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Viagem", null, "600.00",
                mes.plusMonths(3).atEndOfMonth().toString(), null, null, null, null)).valor().id();
        declarar(entrada("meta_protegida", null, null, null, List.of(meta), null));

        ProjecaoRegistrada registrada = projetar.executar(chamador, new Projetar.Entrada(6, List.of(new Projetar.Cenario("Notebook 12x",
                List.of(new Projetar.Hipotese("compra", "6000.00", "Nubank", 12, null, null, null, null, null, null, null, null, null))))));
        ProjecaoVista p = registrada.projecao();
        assertThat(p.regras().reservaMinima().valor()).isEqualTo("2000.00");
        assertThat(p.regras().tetoParcelas().percentual()).isEqualTo("25.00");
        assertThat(p.regras().metasProtegidas()).extracting(ProjecaoVista.MetaProtegidaVista::id).containsExactly(meta);

        CenarioVisto base = p.linhaBase();
        assertThat(base.meses()).extracting(MesVisto::parcelas).containsOnly("0.00");
        assertThat(base.meses().get(1).renda()).isEqualTo("1000.00");
        assertThat(base.meses().get(0).comprometimento()).isNull();
        assertThat(base.indicadores().mesesSemRenda()).containsExactly(mes.toString());
        assertThat(base.violacoes()).noneMatch(v -> v.regra().equals("teto_parcelas"));

        CenarioVisto notebook = p.cenarios().get(0);
        List<MesVisto> comParcela = notebook.meses().stream().filter(m -> m.parcelas().equals("500.00")).toList();
        assertThat(comParcela).isNotEmpty().allSatisfy(m -> {
            if (!m.mes().equals(mes.toString())) {
                assertThat(m.comprometimento()).isEqualTo("50.00");
            }
        });
        assertThat(notebook.indicadores().picoParcelas().percentual()).isEqualTo("50.00");
        assertThat(notebook.violacoes()).filteredOn(v -> v.regra().equals("teto_parcelas")).isNotEmpty().allSatisfy(v -> {
            assertThat(v.parcelas()).isEqualTo("500.00");
            assertThat(v.teto()).isEqualTo("25.00");
        });
        // A linha de base fica abaixo da reserva no fim do horizonte, e o cenário, mais cedo ou igual.
        assertThat(base.indicadores().mesesAbaixoReservaP80()).isNotEmpty();
        assertThat(notebook.indicadores().mesesAbaixoReservaP80().size()).isGreaterThanOrEqualTo(base.indicadores().mesesAbaixoReservaP80().size());
        assertThat(notebook.violacoes()).filteredOn(v -> v.regra().equals("reserva_minima")).allSatisfy(v -> assertThat(v.limite()).isEqualTo("2000.00"));
        assertThat(notebook.violacoes()).filteredOn(v -> v.regra().equals("meta_protegida")).allSatisfy(v -> {
            assertThat(v.meta()).isEqualTo(meta);
            assertThat(v.nomeMeta()).isEqualTo("Viagem");
            assertThat(v.aporteMensalNecessario()).isEqualTo("150.00");
            assertThat(YearMonth.parse(v.mes()).isAfter(mes.plusMonths(3))).isFalse();
        });
        assertThat(p.notas()).anyMatch(n -> n.contains("violacoes"));
        assertThat(p.ressalvas()).extracting(r -> r.codigo()).contains("mes_sem_renda");

        // A tela do retrato mostra as regras quebradas, sem os textos escritos para o assistente.
        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrada.urlRetrato().substring(registrada.urlRetrato().indexOf("/fin/")),
                null, null, null, null, null, null));
        List<String> textos = textos(tela);
        assertThat(textos).anyMatch(t -> t.contains("Regras que você declarou"));
        assertThat(textos).noneMatch(t -> t.contains("definir_compromisso") || t.contains("Não estime") || t.contains("violacoes"));
        assertThat(tela.dados()).containsKey("cenario0_violacoes");
    }

    @Test
    void ritmoTrazAVariabilidadeEAPoupancaDeclaradaContraAObservada() {
        historico(true);
        declarar(entrada("intencao_poupanca", null, null, "30", null, null));
        RitmoRegistrado registrado = ritmo.executar(chamador, new RitmoGastos.Entrada(null, null, null, null));
        RitmoVisto r = registrado.ritmo();

        // Mercado: 100,00 a 600,00; mediana 350,00, p25 200,00, p75 500,00: dispersão de 300,00 sobre 350,00.
        assertThat(r.variabilidade().categorias()).extracting(RitmoVisto.VariabilidadeCategoria::nome).containsExactly("Mercado", "Lazer");
        RitmoVisto.VariabilidadeCategoria mercado = r.variabilidade().categorias().get(0);
        assertThat(mercado.menor()).isEqualTo("100.00");
        assertThat(mercado.mediana()).isEqualTo("350.00");
        assertThat(mercado.maior()).isEqualTo("600.00");
        assertThat(mercado.dispersao()).isEqualTo("85.71");
        assertThat(mercado.porMes()).containsExactly("100.00", "200.00", "300.00", "400.00", "500.00", "600.00");
        assertThat(r.variabilidade().categorias().get(1).dispersao()).isEqualTo("0.00");

        assertThat(r.poupanca().declarada()).isEqualTo("30.00");
        assertThat(r.poupanca().observada()).isEqualTo("40.00");
        assertThat(r.poupanca().abaixo()).isFalse();
        assertThat(r.poupanca().meses()).containsExactly(mes.minusMonths(3).toString(), mes.minusMonths(2).toString(), mes.minusMonths(1).toString());

        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrado.urlRetrato().substring(registrado.urlRetrato().indexOf("/fin/")),
                null, null, null, null, null, null));
        assertThat(textos(tela)).anyMatch(t -> t.contains("poupou o que declarou"));
        assertThat(tela.dados()).containsKey("variabilidade");
    }

    // ---- apoio ----

    private static SinalVisto sinal(Situacao s, String codigo) {
        return s.sinais().stream().filter(x -> x.codigo().equals(codigo)).findFirst()
                .orElseThrow(() -> new AssertionError("Sem " + codigo + " em " + s.sinais()));
    }

    private DeclaracaoVista vigente(String tipo) {
        return obterContexto.executar(chamador).declaracoes().stream().filter(d -> d.tipo().equals(tipo)).findFirst().orElseThrow();
    }

    private ResultadoEscrita<DeclaracaoRegistrada> declarar(Declarar.Entrada e) {
        return declarar.executar(chamador, e);
    }

    private static Declarar.Entrada entrada(String tipo, String valor, Integer meses, String percentual, List<String> metas, String texto) {
        return new Declarar.Entrada(chave(), null, tipo, null, null, null, valor, meses, percentual, metas, texto);
    }

    private void confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private void compromisso(String direcao, String tipo, String nome, String valor, LocalDate vencimento) {
        definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, direcao, tipo, nome, null, valor, null, null,
                null, null, vencimento.toString(), null, null, null, null, "Itaú", null, null, null, null));
    }

    private void gasto(String valor, LocalDate data, String categoria, String meio, Integer parcelas) {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra", meio, "conversa", data.toString(), categoria,
                null, null, null, null, null, null, true, null, null, parcelas, null));
    }

    private void ganho(String valor, LocalDate data) {
        registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, valor, "Salário", "Itaú", "Salário", "conversa",
                data.toString(), null, null, null, null, null, true, null, null));
    }

    private void cartao(String nome, int fechamento, int vencimento) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", nome, null, null, null, null, "2020-01-01",
                null, new DadosCartao(fechamento, vencimento, null, "Itaú")));
    }

    private void conta(String tipo, String nome, String saldo, String data) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, data, null));
    }

    /** O texto de cada componente de texto, com as referências resolvidas nos dados da tela. */
    private static List<String> textos(Vista tela) {
        List<String> textos = new ArrayList<>();
        for (Map<String, Object> c : tela.componentes()) {
            if (!"texto".equals(c.get("componente"))) {
                continue;
            }
            Object valor = c.get("valor");
            if (valor instanceof Map<?, ?> ref) {
                Object atual = tela.dados();
                for (String parte : ref.get("caminho").toString().substring(1).split("/")) {
                    atual = atual instanceof Map<?, ?> m ? m.get(parte) : ((List<?>) atual).get(Integer.parseInt(parte));
                }
                valor = atual;
            }
            textos.add(String.valueOf(valor));
        }
        return textos;
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable acao) {
        try {
            acao.run();
        } catch (ErroTipado e) {
            return e;
        }
        throw new AssertionError("Era esperado um erro");
    }
}
