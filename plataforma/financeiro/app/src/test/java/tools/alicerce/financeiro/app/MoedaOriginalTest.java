package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

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
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto;
import tools.alicerce.financeiro.aplicacao.ListarLancamentos;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.OriginalVisto;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;

/** Testes de aceite, nos casos de uso, contra o banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class MoedaOriginalTest {

    private static final String DATA = "2026-10-30";

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired CorrigirLancamento corrigir;
    @Autowired Desfazer desfazer;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarLancamentos listar;
    @Autowired ObterContexto contexto;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "corrente", "Itaú", null, null, null, "1000.00", "2020-01-01", null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "categoria", "Assinaturas", null, null, null, null, null, null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "fonte_renda", "Consultoria", null, null, null, null, null, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
        assertThat(banco.fetch("""
                select l.id from lancamento l join movimento m on m.lancamento_id = l.id
                where l.dono_id = ? group by l.id having sum(m.valor_centavos) <> 0""", dono)).isEmpty();
    }

    @Test
    void gastoComValorInformadoGuardaOriginalECotacaoImplicita() {
        LancamentoRegistrado gasto = gasto("110.40", usd("20.00", null)).valor();

        assertThat(gasto.valor()).isEqualTo("110.40");
        assertThat(gasto.moeda()).isEqualTo("BRL");
        assertThat(gasto.saldoConta()).isEqualTo("889.60");
        assertThat(gasto.original()).isEqualTo(new OriginalVisto("20.00", "USD", "5.52", DATA));
        assertThat(banco.fetchSingle("select valor_original_minimo, moeda_original from lancamento where operacao_id = (select operacao_id from lancamento l join referencia_curta r on r.entidade_id = l.id where r.codigo = ?)", gasto.id()).intoList())
                .containsExactly(2000L, "USD");
    }

    @Test
    void gastoComCotacaoInformadaTemOValorCalculadoNoDominio() {
        LancamentoRegistrado gasto = gasto(null, usd("19.99", "5.5255")).valor();

        // 19,99 × 5,5255 = 110,454745
        assertThat(gasto.valor()).isEqualTo("110.45");
        assertThat(gasto.original().cotacao()).isEqualTo("5.5255");
    }

    @Test
    void ganhoEmOutraMoedaESimulacao() {
        var entrada = new RegistrarGanho.Entrada(chave(), null, "552.00", "Projeto", "Itaú", "Consultoria", null, DATA, null, null, null, null,
                null, null, true, new EntradaMoeda("100.00", "usd", null, "2026-10-28"));

        LancamentoRegistrado simulado = registrarGanho.executar(chamador, entrada).valor();

        assertThat(simulado.id()).isNull();
        assertThat(simulado.original()).isEqualTo(new OriginalVisto("100.00", "USD", "5.52", "2026-10-28"));
        assertThat(banco.fetchCount(banco.selectFrom("lancamento").where("dono_id = ? and moeda_original is not null", dono))).isZero();
    }

    @Test
    void incoerenciaMoedaInexistenteECamposQueFaltam() {
        ErroTipado incoerente = erro(() -> gasto("120.00", usd("20.00", "5.52")));
        assertThat(incoerente.codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(incoerente.mensagem()).contains("110.40", "120.00");

        ErroTipado moeda = erro(() -> gasto("110.40", new EntradaMoeda("20.00", "ZZZ", null, null)));
        assertThat(moeda.codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(moeda.campo()).isEqualTo("moeda_original");

        assertThat(erro(() -> gasto(null, usd("20.00", null))).camposFaltando()).containsExactly("valor");
        assertThat(erro(() -> gasto("20.00", new EntradaMoeda(null, "USD", null, null))).camposFaltando()).containsExactly("valor_original");
        assertThat(erro(() -> gasto("20.00", new EntradaMoeda("20.00", null, null, null))).camposFaltando()).containsExactly("moeda_original");
        assertThat(erro(() -> gasto("110.00", new EntradaMoeda("15.5", "JPY", null, null))).campo()).isEqualTo("valor_original");
    }

    @Test
    void moedaOriginalAparecaNasConsultasEValorSegueEmBrl() {
        LancamentoRegistrado comMoeda = gasto("110.40", usd("20.00", null)).valor();
        gasto("30.00", null);

        LancamentoVisto lido = resolver.executar(chamador, comMoeda.id());
        assertThat(lido.valor()).isEqualTo("110.40");
        assertThat(lido.original()).isEqualTo(new OriginalVisto("20.00", "USD", "5.52", DATA));
        var pagina = listar.executar(chamador, new ListarLancamentos.Entrada(DATA, DATA, null, null, null, null, null, null, null, null));
        assertThat(pagina.lancamentos()).extracting(LancamentoVisto::valor).containsExactlyInAnyOrder("110.40", "30.00");
        assertThat(pagina.lancamentos()).filteredOn(l -> l.original() == null).hasSize(1);
        assertThat(contexto.executar(chamador).contas()).filteredOn(c -> c.nome().equals("Itaú")).singleElement()
                .satisfies(c -> assertThat(c.saldo()).isEqualTo("859.60"));
    }

    @Test
    void corrigirSoOValorDoExtratoRecalculaACotacao() {
        LancamentoRegistrado gasto = gasto("110.40", usd("20.00", null)).valor();

        CorrecaoRegistrada correcao = corrigir(gasto.id(), new Alteracoes("112.00", null, null, null, null, null, null, null, null)).valor();

        assertThat(correcao.original()).isEqualTo(new OriginalVisto("20.00", "USD", "5.6", DATA));
        assertThat(resolver.executar(chamador, correcao.novo()).valor()).isEqualTo("112.00");
    }

    @Test
    void corrigirACotacaoRecalculaOValorECorrigirOOriginalMantemACotacao() {
        LancamentoRegistrado gasto = gasto("110.40", usd("20.00", null)).valor();

        CorrecaoRegistrada cotacao = corrigir(gasto.id(), moeda(new EntradaMoeda(null, null, "5.60", null))).valor();
        assertThat(resolver.executar(chamador, cotacao.novo()).valor()).isEqualTo("112.00");

        CorrecaoRegistrada original = corrigir(cotacao.novo(), moeda(new EntradaMoeda("25.00", null, null, null))).valor();
        assertThat(resolver.executar(chamador, original.novo()).valor()).isEqualTo("140.00");
        assertThat(original.original()).isEqualTo(new OriginalVisto("25.00", "USD", "5.6", DATA));
    }

    @Test
    void corrigirSemMexerNaMoedaMantemOriginalEADataAcompanha() {
        LancamentoRegistrado gasto = gasto("110.41", usd("20.00", "5.52")).valor();

        CorrecaoRegistrada correcao = corrigir(gasto.id(), new Alteracoes(null, "Outra", "2026-10-29", null, null, null, null, null, null)).valor();

        assertThat(correcao.original()).isEqualTo(new OriginalVisto("20.00", "USD", "5.52", "2026-10-29"));
        assertThat(resolver.executar(chamador, correcao.novo()).valor()).isEqualTo("110.41");
    }

    @Test
    void lancamentoEmReaisGanhaMoedaOriginalSemMudarOValor() {
        LancamentoRegistrado gasto = gasto("110.40", null).valor();

        assertThat(erro(() -> corrigir(gasto.id(), moeda(new EntradaMoeda("20.00", null, null, null)))).camposFaltando())
                .containsExactly("alteracoes.moeda_original");

        CorrecaoRegistrada correcao = corrigir(gasto.id(), moeda(usd("20.00", null))).valor();
        assertThat(correcao.original().cotacao()).isEqualTo("5.52");
        assertThat(resolver.executar(chamador, correcao.novo()).valor()).isEqualTo("110.40");
    }

    @Test
    void lancamentoComItensNaoMudaDeValorPelaMoeda() {
        var itens = java.util.List.of(new RegistrarGasto.Item("Plano", null, "110.40", "Assinaturas", null));
        LancamentoRegistrado gasto = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "110.40", "Plano", "Pix",
                "conversa", DATA, null, null, itens, null, null, null, null, null, null, usd("20.00", null))).valor();

        assertThat(erro(() -> corrigir(gasto.id(), moeda(new EntradaMoeda(null, null, "6.00", null)))).campo()).isEqualTo("alteracoes.valor");
        assertThat(corrigir(gasto.id(), moeda(new EntradaMoeda(null, null, null, "2026-10-01"))).valor().original().dataCotacao()).isEqualTo("2026-10-01");
    }

    @Test
    void desfazerERefazerPreservamOOriginal() {
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("110.40", usd("20.00", null));
        String operacao = gasto.operacao().referencia().id();

        ResultadoEscrita<ReversaoRegistrada> desfeita = confirmar(operacao);
        assertThat(resolver.executar(chamador, gasto.valor().id()).anulado()).isTrue();

        confirmar(desfeita.operacao().referencia().id());
        LancamentoVisto restabelecido = resolver.executar(chamador, gasto.valor().id());
        assertThat(restabelecido.anulado()).isFalse();
        assertThat(restabelecido.original()).isEqualTo(new OriginalVisto("20.00", "USD", "5.52", DATA));
        assertThat(banco.fetchCount(banco.selectFrom("lancamento").where("dono_id = ? and tipo = 'estorno' and moeda_original = 'USD'", dono))).isEqualTo(2);
    }

    private ResultadoEscrita<ReversaoRegistrada> confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        return desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private static EntradaMoeda usd(String valor, String cotacao) {
        return new EntradaMoeda(valor, "USD", cotacao, null);
    }

    private static Alteracoes moeda(EntradaMoeda moeda) {
        return new Alteracoes(null, null, null, null, null, null, null, null, null, moeda);
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String valor, EntradaMoeda moeda) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Assinatura", "Pix", "conversa", DATA,
                "Assinaturas", null, null, null, null, null, null, true, null, moeda));
    }

    private ResultadoEscrita<CorrecaoRegistrada> corrigir(String lancamento, Alteracoes alteracoes) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, "corrigir", alteracoes, "erro", null));
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
