package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

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
import tools.alicerce.financeiro.aplicacao.CorrecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.OperacaoVista;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoRegistrado;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.LinhaVista;
import tools.alicerce.financeiro.aplicacao.RetratoVisto;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;

/** Testes de aceite, nos casos de uso, contra o banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class ResumoPeriodoTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired CorrigirLancamento corrigir;
    @Autowired Desfazer desfazer;
    @Autowired ResumirPeriodo resumir;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
        conta("corrente", "Itaú", null, "1000.00");
        conta("corrente", "Nubank", null, "500.00");
        conta("categoria", "Casa", null, null);
        conta("categoria", "Mercado", id("Casa"), null);
        conta("categoria", "Luz", id("Casa"), null);
        conta("categoria", "Lazer", null, null);
        conta("fonte_renda", "Salário", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "debito", "Débito", null, "Nubank", true, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, true, null, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Mãe", null, null, null, null));
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    @Test
    void entradasSaidasESaldoSemSaldoDeAberturaEComQuebras() {
        gasto("300.00", "Mercado", "Pix", "2026-09-05", "Pão de Açúcar", null);
        gasto("100.00", "Luz", "Débito", "2026-09-10", "Enel", null);
        gasto("100.00", "Lazer", "Pix", "2026-09-30", "pão de açúcar", null);
        gasto("999.00", "Lazer", "Pix", "2026-10-01", null, null);
        ganho("2000.00", "2026-09-15");

        ResumoVisto r = resumo("2026-09", List.of("categoria", "meio_pagamento", "contraparte", "conta", "ambito"), null, null).resumo();

        assertThat(r.de()).isEqualTo("2026-09-01");
        assertThat(r.ate()).isEqualTo("2026-09-30");
        assertThat(r.entradas()).isEqualTo("2000.00");
        assertThat(r.saidas()).isEqualTo("500.00");
        assertThat(r.saldo()).isEqualTo("1500.00");
        assertThat(r.quantidadeLancamentos()).isEqualTo(4);
        assertThat(r.visao()).isEqualTo("competencia");
        assertThat(r.avisoVisao()).isNull();
        assertThat(linhas(r, 0)).containsExactly("Salário e2000.00 s0.00", "Mercado e0.00 s300.00", "Lazer e0.00 s100.00", "Luz e0.00 s100.00");
        assertThat(r.quebras().get(0).linhas().get(1).participacaoSaidas()).isEqualTo("60.00");
        assertThat(r.quebras().get(0).linhas().get(1).id()).startsWith("a-");
        assertThat(linhas(r, 1)).containsExactly("Sem meio de pagamento e2000.00 s0.00", "Pix e0.00 s400.00", "Débito e0.00 s100.00");
        assertThat(linhas(r, 2)).containsExactly("Sem contraparte e2000.00 s0.00", "Pão de Açúcar e0.00 s400.00", "Enel e0.00 s100.00");
        assertThat(linhas(r, 3)).containsExactly("Itaú e2000.00 s400.00", "Nubank e0.00 s100.00");
        assertThat(linhas(r, 4)).containsExactly("Pessoal e2000.00 s500.00");
        assertThat(r.maioresSaidas()).extracting(ResumoVisto.DestaqueVisto::valor).containsExactly("300.00", "100.00", "100.00");
        assertThat(r.maioresEntradas()).hasSize(1);
    }

    @Test
    void anuladoCorrigidoDesfeitoERefeitoSaemDoTotalCerto() {
        gasto("100.00", "Mercado", "Pix", "2026-09-05", null, null);
        LancamentoRegistrado anulado = gasto("50.00", "Mercado", "Pix", "2026-09-06", null, null).valor();
        LancamentoRegistrado corrigido = gasto("70.00", "Mercado", "Pix", "2026-09-07", null, null).valor();
        LancamentoRegistrado mudaDeMes = gasto("30.00", "Mercado", "Pix", "2026-09-08", null, null).valor();
        ResultadoEscrita<LancamentoRegistrado> desfeito = gasto("20.00", "Mercado", "Pix", "2026-09-09", null, null);
        ResultadoEscrita<LancamentoRegistrado> refeito = gasto("10.00", "Mercado", "Pix", "2026-09-10", null, null);

        acao("anular", anulado.id(), null);
        acao("corrigir", corrigido.id(), new Alteracoes("75.00", null, null, null, null, null, null, null, null));
        CorrecaoRegistrada outroMes = acao("corrigir", mudaDeMes.id(), new Alteracoes(null, null, "2026-10-02", null, null, null, null, null, null));
        confirmar(desfeito.operacao().referencia().id());
        ResultadoEscrita<ReversaoRegistrada> reversao = confirmar(refeito.operacao().referencia().id());
        confirmar(reversao.operacao().referencia().id());

        // 100 + 75 (corrigido) + 10 (desfeito e refeito)
        assertThat(resumo("2026-09", null, null, null).resumo().saidas()).isEqualTo("185.00");
        assertThat(resumo("2026-10", null, null, null).resumo().saidas()).isEqualTo("30.00");
        assertThat(outroMes.novo()).isNotNull();
        assertThat(saidasSomandoOLivroRazao("2026-09-01", "2026-09-30")).isEqualTo(18500);
        assertThat(resumo("2026-09", null, null, null).resumo().quantidadeLancamentos()).isEqualTo(3);
    }

    @Test
    void moedaOriginalNuncaEntraNaSoma() {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "110.40", "Assinatura", "Pix", "conversa", "2026-09-05",
                "Lazer", null, null, null, null, null, null, true, null, new EntradaMoeda("20.00", "USD", null, null)));

        assertThat(resumo("2026-09", null, null, null).resumo().saidas()).isEqualTo("110.40");
    }

    @Test
    void comparaComOPeriodoAnteriorEquivalente() {
        gasto("100.00", "Mercado", "Pix", "2026-08-31", null, null);
        gasto("150.00", "Mercado", "Pix", "2026-09-30", null, null);
        ganho("400.00", "2026-09-02");

        ResumoVisto.PeriodoAnterior anterior = resumo("2026-09", null, null, null).resumo().periodoAnterior();

        assertThat(anterior.de()).isEqualTo("2026-08-01");
        assertThat(anterior.ate()).isEqualTo("2026-08-31");
        assertThat(anterior.saidas()).isEqualTo("100.00");
        assertThat(anterior.variacaoSaidas()).isEqualTo("50.00");
        assertThat(anterior.variacaoSaidasPercentual()).isEqualTo("50.00");
        assertThat(anterior.variacaoEntradasPercentual()).isNull();
        assertThat(anterior.variacaoSaldo()).isEqualTo("350.00");
        assertThat(resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, "2026-09", null, null, null, null, false)).resumo().periodoAnterior()).isNull();
    }

    @Test
    void ambitoFiltraOsMovimentosEProfundidadeSobeNaArvore() {
        gasto("100.00", "Mercado", "Pix", "2026-09-05", null, null);
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "90.00", "Nota", "Pix", "conversa", "2026-09-06", null, null,
                List.of(new RegistrarGasto.Item("Remédio", null, "60.00", "Mercado", "Mãe"), new RegistrarGasto.Item("Conta", null, "30.00", "Luz", "Pessoal")),
                null, null, null, null, true, null));

        ResumoVisto mae = resumo("2026-09", null, "Mãe", null).resumo();
        assertThat(mae.ambito()).isEqualTo("Mãe");
        assertThat(mae.saidas()).isEqualTo("60.00");
        assertThat(mae.maioresSaidas()).singleElement().satisfies(d -> assertThat(d.valor()).isEqualTo("60.00"));
        assertThat(mae.periodoAnterior().saidas()).isEqualTo("0.00");

        assertThat(linhas(resumo("2026-09", List.of("categoria"), null, 1).resumo(), 0)).containsExactly("Casa e0.00 s190.00");
        assertThat(linhas(resumo("2026-09", List.of("categoria"), null, 2).resumo(), 0)).containsExactly("Mercado e0.00 s160.00", "Luz e0.00 s30.00");
    }

    @Test
    void periodosLivresEEntradasInvalidas() {
        gasto("100.00", "Mercado", "Pix", "2026-09-05", null, null);

        ResumoVisto livre = resumir.executar(chamador, new ResumirPeriodo.Entrada("2026-09-01", "2026-09-10", null, null, null, null, "caixa", null)).resumo();
        assertThat(livre.visao()).isEqualTo("caixa");
        assertThat(livre.saidas()).isEqualTo("100.00");
        assertThat(livre.periodoAnterior().de()).isEqualTo("2026-08-22");
        assertThat(resumo("2026-W36", null, null, null).resumo().saidas()).isEqualTo("100.00");
        assertThat(resumo("2026", null, null, null).resumo().saidas()).isEqualTo("100.00");

        assertThat(erro(() -> resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, null, null, null, null, null, null))).camposFaltando())
                .containsExactly("de", "ate");
        assertThat(erro(() -> resumir.executar(chamador, new ResumirPeriodo.Entrada("2026-09-10", "2026-09-01", null, null, null, null, null, null))).codigo())
                .isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(erro(() -> resumir.executar(chamador, new ResumirPeriodo.Entrada("2026-09-01", null, "2026-09", null, null, null, null, null))).campo())
                .isEqualTo("periodo");
        assertThat(erro(() -> resumo("2026-09", List.of("estabelecimento"), null, null)).campo()).isEqualTo("agrupar_por");
        assertThat(erro(() -> resumo("2026-09", null, null, 0)).campo()).isEqualTo("profundidade");
        assertThat(erro(() -> resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, "2026-09", null, null, null, "accrual", null))).campo())
                .isEqualTo("visao");
    }

    @Test
    void retratoNaoMudaEComparaComOCalculoDeAgora() {
        LancamentoRegistrado mercado = gasto("100.00", "Mercado", "Pix", "2026-09-05", null, null).valor();
        ResumoRegistrado resumo = resumo("2026-09", null, null, null);
        assertThat(resumo.retrato()).startsWith("s-");
        assertThat(resumo.urlRetrato()).contains("/fin/s/");

        RetratoVisto igual = (RetratoVisto) resolver.resolver(chamador, resumo.urlRetrato(), true);
        assertThat(igual.comparacao().mudou()).isFalse();
        assertThat(igual.comparacao().linhasAlteradas()).isEmpty();

        acao("corrigir", mercado.id(), new Alteracoes("80.00", null, null, null, null, null, null, null, null));
        gasto("40.00", "Lazer", "Pix", "2026-09-06", null, null);

        RetratoVisto depois = (RetratoVisto) resolver.resolver(chamador, "fin:" + resumo.retrato(), true);
        assertThat(depois.resultado().get("saidas").asString()).isEqualTo("100.00");
        assertThat(depois.capacidade()).isEqualTo("resumo_periodo");
        assertThat(depois.dataReferencia()).isNotBlank();
        assertThat(depois.comparacao().mudou()).isTrue();
        assertThat(depois.comparacao().saidas().retrato()).isEqualTo("100.00");
        assertThat(depois.comparacao().saidas().agora()).isEqualTo("120.00");
        assertThat(depois.comparacao().saidas().diferenca()).isEqualTo("20.00");
        assertThat(depois.comparacao().saldo().diferenca()).isEqualTo("-20.00");
        assertThat(depois.comparacao().linhasAlteradas()).extracting(l -> l.nome() + " " + l.saidas().diferenca())
                .containsExactlyInAnyOrder("Mercado -20.00", "Lazer 40.00");
        assertThat(((RetratoVisto) resolver.resolver(chamador, resumo.retrato())).comparacao()).isNull();
    }

    @Test
    void retratoDeOutroDonoNaoAbre() {
        ResumoRegistrado resumo = resumo("2026-09", null, null, null);
        UUID outro = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, outro).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, outro.toString()).execute();

        assertThat(erro(() -> resolver.resolver(new Chamador(outro, "claude-ai", Set.of("fin:read")), resumo.retrato())).codigo())
                .isEqualTo(CodigoErro.NAO_ENCONTRADO);
    }

    @Test
    void relatorioNaoApareceNaListaDeOperacoesEDesfazerMantemORetrato() {
        ResumoRegistrado resumo = resumo("2026-09", null, null, null);

        assertThat(listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, null, null, null)).operacoes())
                .extracting(OperacaoVista::capacidade).doesNotContain("resumo_periodo");
        List<OperacaoVista> pedidas = listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, "resumo_periodo", null, null)).operacoes();
        assertThat(pedidas).singleElement().satisfies(o -> assertThat(o.resumo()).contains(resumo.retrato()));

        ReversaoRegistrada previa = desfazer.executar(chamador, new Desfazer.Entrada(null, pedidas.get(0).id(), null, null)).valor();
        assertThat(previa.estruturaMantida()).contains("retrato");
        assertThat(resolver.resolver(chamador, resumo.retrato())).isInstanceOf(RetratoVisto.class);
    }

    private long saidasSomandoOLivroRazao(String de, String ate) {
        return banco.fetchSingle("""
                select coalesce(sum(m.valor_centavos), 0) from movimento m join lancamento l on l.id = m.lancamento_id
                join conta c on c.id = m.conta_id
                where l.dono_id = ? and c.classe = 'despesa' and l.data between ?::date and ?::date""", dono, de, ate).get(0, Long.class);
    }

    private static List<String> linhas(ResumoVisto resumo, int quebra) {
        return resumo.quebras().get(quebra).linhas().stream().map(ResumoPeriodoTest::linha).toList();
    }

    private static String linha(LinhaVista l) {
        return l.nome() + " e" + l.entradas() + " s" + l.saidas();
    }

    private ResumoRegistrado resumo(String periodo, List<String> quebras, String ambito, Integer profundidade) {
        return resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, periodo, quebras, ambito, profundidade, null, null));
    }

    private void conta(String tipo, String nome, String pai, String saldo) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, pai, saldo, "2020-01-01", null));
    }

    private String id(String nome) {
        return banco.fetchValue("select id from conta where dono_id = ? and nome = ?", dono, nome).toString();
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String valor, String categoria, String meio, String data, String contraparte, String ambito) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra " + valor, meio, "conversa", data, categoria,
                ambito, null, null, null, contraparte, null, true, null));
    }

    private void ganho(String valor, String data) {
        registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, valor, "Salário", "Itaú", "Salário", null, data, null, null, null,
                null, null, true, null));
    }

    private CorrecaoRegistrada acao(String acao, String lancamento, Alteracoes alteracoes) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, acao, alteracoes, "erro", null)).valor();
    }

    private ResultadoEscrita<ReversaoRegistrada> confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        return desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
