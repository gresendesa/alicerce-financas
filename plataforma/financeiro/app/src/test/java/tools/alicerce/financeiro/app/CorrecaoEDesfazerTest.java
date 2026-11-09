package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CHAVE_FISCAL;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;

import java.util.ArrayList;
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
import tools.alicerce.financeiro.aplicacao.ContaDefinida;
import tools.alicerce.financeiro.aplicacao.Contexto;
import tools.alicerce.financeiro.aplicacao.CorrecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto;
import tools.alicerce.financeiro.aplicacao.ListarLancamentos;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.OperacaoVista;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;

/** Testes de aceite, nos casos de uso, contra o banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class CorrecaoEDesfazerTest {

    private static final String NOTA = "35261011111111111111650010000000011000000019";

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired CorrigirLancamento corrigir;
    @Autowired ListarLancamentos listar;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired Desfazer desfazer;
    @Autowired ObterContexto obterContexto;
    @Autowired ResumirPeriodo resumirPeriodo;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private int chaves;

    @BeforeEach
    void dono() {
        dono = novoDono();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
        assertThat(banco.fetch("""
                select l.id from lancamento l join movimento m on m.lancamento_id = l.id
                where l.dono_id = ? group by l.id having sum(m.valor_centavos) <> 0""", dono)).isEmpty();
    }

    // Correção

    @Test
    void corrigirGeraEstornoENovoEANotaPassaParaONovo() {
        estrutura();
        conta("categoria", "Farmácia", null);
        LancamentoRegistrado errado = gasto("100.00", "Mercado", NOTA, null).valor();

        CorrecaoRegistrada correcao = corrigir("corrigir", errado.id(), new Alteracoes("80.00", null, null, "Farmácia", null, null, null, null, null)).valor();

        assertThat(correcao.estorno()).startsWith("t-");
        assertThat(correcao.novo()).startsWith("t-");
        assertThat(correcao.urlNovo()).contains("/fin/t/");
        assertThat(correcao.saldos()).extracting(s -> s.conta() + " " + s.saldo()).containsExactly("Itaú 920.00");
        assertThat(correcao.movimentos()).extracting(m -> m.conta() + " " + m.valor())
                .containsExactlyInAnyOrder("Farmácia 80.00", "Itaú -80.00");
        assertThat(saldo("Itaú")).isEqualTo("920.00");
        assertThat(apontadoPelaNota()).isEqualTo(idDe(correcao.novo()));

        assertThat(listar().lancamentos()).extracting(LancamentoVisto::id).containsExactly(correcao.novo(), abertura());
        assertThat(listar(true).lancamentos()).extracting(LancamentoVisto::id)
                .contains(errado.id(), correcao.estorno(), correcao.novo());
        LancamentoVisto anulado = resolver.executar(chamador, errado.id());
        assertThat(anulado.anulado()).isTrue();
        assertThat(anulado.estornadoPor()).isEqualTo(correcao.estorno());
        assertThat(resolver.executar(chamador, correcao.estorno()).estorna()).isEqualTo(errado.id());

        ErroTipado duplicada = erro(() -> gasto("80.00", "Mercado", NOTA, null));
        assertThat(duplicada.codigo()).isEqualTo(CodigoErro.NOTA_DUPLICADA);
        assertThat(duplicada.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(correcao.novo());
    }

    @Test
    void anularSomeDasConsultasDevolveOSaldoELiberaANota() {
        estrutura();
        LancamentoRegistrado errado = gasto("100.00", "Mercado", NOTA, null).valor();

        CorrecaoRegistrada anulacao = corrigir("anular", errado.id(), null).valor();

        assertThat(anulacao.novo()).isNull();
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
        assertThat(listar().lancamentos()).extracting(LancamentoVisto::id).containsExactly(abertura());
        assertThat(registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "100.00", "Compra", "Pix Itaú",
                "conversa", null, "Mercado", null, null, null, NOTA, null, null, null, null)).valor().id()).isNotNull();
    }

    @Test
    void naoCorrigeLancamentoJaEstornadoNemUmEstorno() {
        estrutura();
        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();
        CorrecaoRegistrada anulacao = corrigir("anular", gasto.id(), null).valor();

        assertThat(erro(() -> corrigir("anular", gasto.id(), null)).codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(erro(() -> corrigir("anular", anulacao.estorno(), null)).codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
    }

    @Test
    void correcaoExigeOQueMudaEOMotivo() {
        estrutura();
        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();

        ErroTipado erro = erro(() -> corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, gasto.id(), "corrigir", null, null, null)));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
        assertThat(erro.camposFaltando()).containsExactly("motivo", "alteracoes");
        assertThat(erro(() -> corrigir("trocar", gasto.id(), null)).codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
    }

    @Test
    void comItensSoMudaOQueNaoMexeNosValores() {
        estrutura();
        LancamentoRegistrado gasto = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "30.00", "Feira", "Pix Itaú",
                "conversa", null, "Mercado", null, List.of(new RegistrarGasto.Item("Arroz", "2", "30.00", null, null)), null, null, null, null, null, null)).valor();

        assertThat(erro(() -> corrigir("corrigir", gasto.id(), new Alteracoes("40.00", null, null, null, null, null, null, null, null))).codigo())
                .isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        CorrecaoRegistrada correcao = corrigir("corrigir", gasto.id(), new Alteracoes(null, "Feira de sábado", "2026-10-01", null, null, null, null, null, null)).valor();

        LancamentoVisto novo = resolver.executar(chamador, correcao.novo());
        assertThat(novo.descricao()).isEqualTo("Feira de sábado");
        assertThat(novo.data()).isEqualTo("2026-10-01");
        assertThat(novo.itens()).extracting(LancamentoVisto.ItemVisto::descricao).containsExactly("Arroz");
    }

    @Test
    void trocaMeioDoGastoEContaDoGanhoESaldoDeAbertura() {
        estrutura();
        conta("corrente", "Nubank", "500.00");
        conta("fonte_renda", "Salário", null);
        meio("pix", "Pix Nu", "Nubank");
        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();
        LancamentoRegistrado ganho = registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, "100.00", "Bico", "Itaú", "Salário",
                null, null, null, null, null, null, null, null, null)).valor();

        corrigir("corrigir", gasto.id(), new Alteracoes(null, null, null, null, null, "Pix Nu", null, null, null));
        corrigir("corrigir", ganho.id(), new Alteracoes(null, null, null, null, null, null, "Nubank", null, null));

        assertThat(saldo("Itaú")).isEqualTo("1000.00");
        assertThat(saldo("Nubank")).isEqualTo("590.00");
        corrigir("corrigir", abertura(), new Alteracoes("1500.00", null, null, null, null, null, null, null, null));
        assertThat(saldo("Itaú")).isEqualTo("1500.00");
    }

    @Test
    void simularCorrecaoNaoGravaERepetirNaoCorrigeDeNovo() {
        estrutura();
        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();
        int antes = banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono));

        ResultadoEscrita<CorrecaoRegistrada> simulado = corrigir.executar(chamador, new CorrigirLancamento.Entrada(null, null, gasto.id(), "corrigir",
                new Alteracoes("20.00", null, null, null, null, null, null, null, null), "valor errado", true));

        assertThat(simulado.simulado()).isTrue();
        assertThat(simulado.valor().saldos()).extracting(s -> s.saldo()).containsExactly("980.00");
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono))).isEqualTo(antes);

        CorrigirLancamento.Entrada entrada = new CorrigirLancamento.Entrada(chave(), null, gasto.id(), "anular", null, "engano", null);
        corrigir.executar(chamador, entrada);
        assertThat(corrigir.executar(chamador, entrada).repetido()).isTrue();
    }

    // Consultas

    @Test
    void filtraPorDataTextoValorMeioECategoriaComSubarvore() {
        estrutura();
        conta("categoria", "Hortifruti", "Mercado", null);
        conta("categoria", "Lazer", null);
        registrar("10.00", "Maçã", "Hortifruti", "2026-09-10");
        registrar("20.00", "Arroz", "Mercado", "2026-10-10");
        registrar("30.00", "Cinema 100%", "Lazer", "2026-10-20");

        assertThat(descricoes(new ListarLancamentos.Entrada(null, null, null, "Mercado", null, null, null, null, null, null)))
                .containsExactly("Arroz", "Maçã");
        assertThat(descricoes(new ListarLancamentos.Entrada("2026-10-01", "2026-10-15", null, null, "Pix Itaú", null, null, null, null, null)))
                .containsExactly("Arroz");
        assertThat(descricoes(new ListarLancamentos.Entrada(null, null, null, null, null, "100%", null, null, null, null)))
                .containsExactly("Cinema 100%");
        assertThat(descricoes(new ListarLancamentos.Entrada(null, null, null, null, "Pix Itaú", null, "15.00", "25.00", null, null)))
                .containsExactly("Arroz");
    }

    @Test
    void paginaDe50ComCursorSemRepetirNemPular() {
        estrutura();
        for (int i = 0; i < 55; i++) {
            registrar("1.00", "Item " + i, "Mercado", "2026-10-" + String.format("%02d", 1 + i % 10));
        }

        ListarLancamentos.Pagina primeira = listar.executar(chamador, filtroCursor(null));
        ListarLancamentos.Pagina segunda = listar.executar(chamador, filtroCursor(primeira.proximoCursor()));

        assertThat(primeira.lancamentos()).hasSize(50);
        assertThat(primeira.proximoCursor()).isNotBlank();
        assertThat(segunda.lancamentos()).hasSize(5);
        assertThat(segunda.proximoCursor()).isNull();
        List<String> todos = new ArrayList<>(primeira.lancamentos().stream().map(LancamentoVisto::id).toList());
        todos.addAll(segunda.lancamentos().stream().map(LancamentoVisto::id).toList());
        assertThat(todos).doesNotHaveDuplicates();
        assertThat(erro(() -> listar.executar(chamador, filtroCursor("lixo"))).codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
    }

    @Test
    void resolveIdCurtoIdCompletoEUrlERecusaOutrosCasos() {
        estrutura();
        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();

        assertThat(resolver.executar(chamador, gasto.id()).descricao()).isEqualTo("Compra");
        assertThat(resolver.executar(chamador, "fin:" + gasto.id()).id()).isEqualTo(gasto.id());
        LancamentoVisto porUrl = resolver.executar(chamador, gasto.url());
        assertThat(porUrl.id()).isEqualTo(gasto.id());
        assertThat(porUrl.operacao()).startsWith("o-");
        assertThat(porUrl.movimentos()).hasSize(2);

        assertThat(erro(() -> resolver.executar(chamador, "pla:t-8K3QZ2")).codigo()).isEqualTo(CodigoErro.MODULO_ERRADO);
        assertThat(erro(() -> resolver.executar(chamador, "t-000000")).codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
        Chamador outro = new Chamador(novoDono(), "claude-ai", Set.of("fin:read"));
        assertThat(erro(() -> resolver.executar(outro, gasto.id())).codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
    }

    @Test
    void listaOperacoesComResumoEFiltros() {
        estrutura();
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), "nota-1", "1234.50", "Almoço", "Pix Itaú", "conversa",
                null, "Mercado", null, null, null, null, null, null, null, null));
        for (int i = 0; i < 20; i++) {
            registrar("1.00", "Item", "Mercado", null);
        }

        assertThat(listarOperacoes.executar(chamador, filtro(null, null, null)).operacoes()).hasSize(20);
        ListarOperacoes.Pagina lote = listarOperacoes.executar(chamador, filtro("nota-1", null, null));
        OperacaoVista gasto = lote.operacoes().get(0);
        assertThat(gasto.resumo()).isEqualTo("gasto de R$ 1.234,50 em Mercado, Pix Itaú");
        assertThat(gasto.lote()).isEqualTo("nota-1");
        assertThat(gasto.cliente()).isEqualTo("claude-ai");
        assertThat(gasto.estado()).isEqualTo("aplicada");
        assertThat(gasto.url()).contains("/fin/o/");

        confirmar(gasto.id());
        assertThat(listarOperacoes.executar(chamador, filtro("nota-1", null, null)).operacoes()).isEmpty();
        assertThat(listarOperacoes.executar(chamador, filtro("nota-1", null, true)).operacoes())
                .extracting(OperacaoVista::estado).containsExactly("desfeita");
        assertThat(listarOperacoes.executar(chamador, filtro(null, "desfazer", null)).operacoes().get(0).resumo())
                .isEqualTo("desfez " + gasto.id());
    }

    // Desfazer

    @Test
    void previaNaoMudaNadaEOTokenAplica() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("100.00", "Mercado", null, null);
        int lancamentos = banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono));

        ResultadoEscrita<ReversaoRegistrada> previa = previa(gasto);

        assertThat(previa.simulado()).isTrue();
        assertThat(previa.valor().confirmacao()).isNotBlank();
        assertThat(previa.valor().lancamentosEstornados()).extracting(l -> l.id() + " " + l.valor()).containsExactly(gasto.valor().id() + " 100.00");
        assertThat(previa.valor().saldos()).extracting(s -> s.conta() + " " + s.saldo()).containsExactly("Itaú 1000.00");
        assertThat(saldo("Itaú")).isEqualTo("900.00");
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono))).isEqualTo(lancamentos);
        assertThat(estado(gasto.operacao().id())).isEqualTo("aplicada");

        ResultadoEscrita<ReversaoRegistrada> aplicado = aplicar(gasto.operacao().referencia().id(), null, previa.valor().confirmacao());

        assertThat(aplicado.operacao().referencia().id()).startsWith("o-");
        assertThat(aplicado.valor().confirmacao()).isNull();
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
        assertThat(estado(gasto.operacao().id())).isEqualTo("desfeita");
        assertThat(listar().lancamentos()).extracting(LancamentoVisto::id).containsExactly(abertura());
    }

    @Test
    void repetirAConfirmacaoComAMesmaChaveNaoDesfazDuasVezes() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("100.00", "Mercado", null, null);
        String token = previa(gasto).valor().confirmacao();
        Desfazer.Entrada entrada = new Desfazer.Entrada(chave(), gasto.operacao().referencia().id(), null, token);

        desfazer.executar(chamador, entrada);
        ResultadoEscrita<ReversaoRegistrada> repetida = desfazer.executar(chamador, entrada);

        assertThat(repetida.repetido()).isTrue();
        assertThat(repetida.valor().lancamentosEstornados()).hasSize(1);
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
    }

    @Test
    void loteDeTresEstornaOsTresNumaTransacao() {
        estrutura();
        for (String valor : List.of("1.00", "2.00", "3.00")) {
            registrarNoLote("nota", valor);
        }

        ReversaoRegistrada previa = desfazer.executar(chamador, new Desfazer.Entrada(null, null, "nota", null)).valor();
        aplicar(null, "nota", previa.confirmacao());

        assertThat(previa.operacoes()).hasSize(3);
        assertThat(previa.lancamentosEstornados()).extracting(ReversaoRegistrada.LancamentoRevertido::valor).containsExactly("3.00", "2.00", "1.00");
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
        assertThat(erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, null, "nota", null))).codigo()).isEqualTo(CodigoErro.JA_DESFEITA);
        assertThat(erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, null, "outro", null))).codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
    }

    @Test
    void falhaNoTerceiroDeixaOsTresIntactos() {
        estrutura();
        LancamentoRegistrado primeiro = registrarNoLote("nota", "1.00");
        registrarNoLote("nota", "2.00");
        registrarNoLote("nota", "3.00");
        // O primeiro é o último a ser revertido, e agora a reversão dele falha.
        CorrecaoRegistrada fora = corrigir("anular", primeiro.id(), null).valor();
        int lancamentos = banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono));

        ErroTipado erro = erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, null, "nota", null)));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono))).isEqualTo(lancamentos);
        assertThat(banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(dono).and(OPERACAO.ESTADO.eq("desfeita")))).isZero();
        assertThat(saldo("Itaú")).isEqualTo("995.00");
        assertThat(fora.estorno()).isNotNull();
    }

    @Test
    void contaComLancamentosBloqueiaListandoAsDependentesEOConjuntoDesfaz() {
        estrutura();
        ResultadoEscrita<ContaDefinida> nubank = conta("corrente", "Nubank", null);
        ResultadoEscrita<?> meio = meio("pix", "Pix Nu", "Nubank");
        ResultadoEscrita<LancamentoRegistrado> gasto = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "5.00", "Café", "Pix Nu", "conversa",
                null, "Mercado", null, null, null, null, null, null, null, null));

        ErroTipado erro = erro(() -> previa(nubank));
        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id)
                .containsExactlyInAnyOrder(meio.operacao().referencia().id(), gasto.operacao().referencia().id());
        assertThat(erro.candidatos()).allSatisfy(c -> assertThat(c.url()).contains("/fin/o/"));

        confirmar(gasto.operacao().referencia().id());
        confirmar(meio.operacao().referencia().id());
        ReversaoRegistrada arquivada = confirmar(nubank.operacao().referencia().id());

        assertThat(arquivada.entidadesArquivadas()).extracting(ReversaoRegistrada.EntidadeRevertida::nome).containsExactly("Nubank");
        assertThat(obterContexto.executar(chamador).contas()).extracting(Contexto.ContaContexto::nome).doesNotContain("Nubank");
    }

    @Test
    void desfazerODesfazerRestabeleceEDePoisDesfazDeNovo() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("100.00", "Mercado", NOTA, null);
        String op = gasto.operacao().referencia().id();

        ResultadoEscrita<ReversaoRegistrada> desfeito = aplicar(op, null, previa(gasto).valor().confirmacao());
        assertThat(erro(() -> previa(gasto)).codigo()).isEqualTo(CodigoErro.JA_DESFEITA);
        assertThat(erro(() -> previa(gasto)).candidatos()).extracting(ErroTipado.Candidato::id)
                .containsExactly(desfeito.operacao().referencia().id());

        String idDesfazer = desfeito.operacao().referencia().id();
        ReversaoRegistrada refeito = confirmar(idDesfazer);

        assertThat(refeito.lancamentosRestabelecidos()).extracting(ReversaoRegistrada.LancamentoRevertido::id).containsExactly(gasto.valor().id());
        assertThat(saldo("Itaú")).isEqualTo("900.00");
        assertThat(estado(gasto.operacao().id())).isEqualTo("aplicada");
        assertThat(erro(() -> confirmar(idDesfazer)).codigo()).isEqualTo(CodigoErro.JA_DESFEITA);
        assertThat(listar().lancamentos()).extracting(LancamentoVisto::id).containsExactly(gasto.valor().id(), abertura());
        LancamentoVisto restabelecido = resolver.executar(chamador, gasto.valor().id());
        assertThat(restabelecido.anulado()).isFalse();
        assertThat(restabelecido.estornadoPor()).isNull();

        confirmar(op);
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
        assertThat(estado(gasto.operacao().id())).isEqualTo("desfeita");
    }

    @Test
    void desfazerACorrecaoVoltaAoOriginalComANotaERefazerVoltaAoNovo() {
        estrutura();
        LancamentoRegistrado errado = gasto("100.00", "Mercado", NOTA, null).valor();
        ResultadoEscrita<CorrecaoRegistrada> correcao = corrigir("corrigir", errado.id(), new Alteracoes("80.00", null, null, null, null, null, null, null, null));
        String op = correcao.operacao().referencia().id();

        ResultadoEscrita<ReversaoRegistrada> desfeita = desfazerDe(op);

        assertThat(saldo("Itaú")).isEqualTo("900.00");
        assertThat(apontadoPelaNota()).isEqualTo(idDe(errado.id()));
        assertThat(desfeita.valor().lancamentosRestabelecidos()).extracting(ReversaoRegistrada.LancamentoRevertido::id).containsExactly(errado.id());
        assertThat(desfeita.valor().lancamentosEstornados()).extracting(ReversaoRegistrada.LancamentoRevertido::id).containsExactly(correcao.valor().novo());
        assertThat(listar().lancamentos()).extracting(LancamentoVisto::id).containsExactly(errado.id(), abertura());

        confirmar(desfeita.operacao().referencia().id());

        assertThat(saldo("Itaú")).isEqualTo("920.00");
        assertThat(apontadoPelaNota()).isEqualTo(idDe(correcao.valor().novo()));

        confirmar(op);
        assertThat(saldo("Itaú")).isEqualTo("900.00");
        assertThat(apontadoPelaNota()).isEqualTo(idDe(errado.id()));
    }

    @Test
    void lancamentoCorrigidoDependeDaCorrecao() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("100.00", "Mercado", null, null);
        ResultadoEscrita<CorrecaoRegistrada> correcao = corrigir("anular", gasto.valor().id(), null);

        ErroTipado erro = erro(() -> previa(gasto));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(correcao.operacao().referencia().id());
    }

    @Test
    void desfazerAnulacaoComANotaEmOutroLancamentoBloqueia() {
        estrutura();
        LancamentoRegistrado primeiro = gasto("100.00", "Mercado", NOTA, null).valor();
        ResultadoEscrita<CorrecaoRegistrada> anulacao = corrigir("anular", primeiro.id(), null);
        ResultadoEscrita<LancamentoRegistrado> outro = gasto("90.00", "Mercado", NOTA, null);

        ErroTipado erro = erro(() -> confirmar(anulacao.operacao().referencia().id()));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(outro.operacao().referencia().id());

        confirmar(outro.operacao().referencia().id());
        confirmar(anulacao.operacao().referencia().id());
        assertThat(apontadoPelaNota()).isEqualTo(idDe(primeiro.id()));
        assertThat(saldo("Itaú")).isEqualTo("900.00");
    }

    @Test
    void notaEmLancamentoAnuladoDeOutraOperacaoTambemBloqueia() {
        estrutura();
        LancamentoRegistrado primeiro = gasto("100.00", "Mercado", NOTA, null).valor();
        ResultadoEscrita<CorrecaoRegistrada> anulacao = corrigir("anular", primeiro.id(), null);
        ResultadoEscrita<LancamentoRegistrado> outro = gasto("90.00", "Mercado", NOTA, null);
        corrigir("anular", outro.valor().id(), null);

        ErroTipado erro = erro(() -> confirmar(anulacao.operacao().referencia().id()));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(outro.operacao().referencia().id());
    }

    @Test
    void alteracaoVoltaAVersaoAnteriorEDependenteMaisNovaBloqueia() {
        conta("categoria", "Mercado", null);
        ResultadoEscrita<ContaDefinida> primeira = alterarConta("Mercado", "Supermercado");
        ResultadoEscrita<ContaDefinida> segunda = alterarConta("Supermercado", "Feira");

        ErroTipado erro = erro(() -> previa(primeira));
        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(erro.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(segunda.operacao().referencia().id());

        ReversaoRegistrada desfeita = confirmar(segunda.operacao().referencia().id());
        assertThat(desfeita.versoesRestauradas()).extracting(ReversaoRegistrada.EntidadeRevertida::nome).containsExactly("Supermercado");
        confirmar(primeira.operacao().referencia().id());
        assertThat(obterContexto.executar(chamador).contas()).extracting(Contexto.ContaContexto::nome).containsExactly("Mercado");
    }

    @Test
    void criarAmbitoPadraoSeDesfazDevolvendoOPadraoAnterior() {
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, null, null, null));
        var familia = definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Família", null, true, null, null));

        confirmar(familia.operacao().referencia().id());

        assertThat(obterContexto.executar(chamador).ambitos()).extracting(a -> a.nome() + " " + a.padrao()).containsExactly("Pessoal true");
    }

    @Test
    void estruturaCriadaSozinhaFicaEOutrasOperacoesNaoDependemDela() {
        conta("categoria", "Mercado", null);
        ResultadoEscrita<ContaDefinida> itau = conta("corrente", "Itaú", "1000.00");
        conta("corrente", "Nubank", "500.00");
        meio("pix", "Pix Itaú", "Itaú");
        ResultadoEscrita<LancamentoRegistrado> primeiro = gasto("10.00", "Mercado", null, null);
        gasto("20.00", "Mercado", null, null);

        ReversaoRegistrada desfeito = confirmar(primeiro.operacao().referencia().id());

        assertThat(desfeito.estruturaMantida()).containsExactly("Pessoal");
        assertThat(obterContexto.executar(chamador).ambitos()).extracting(Contexto.AmbitoContexto::nome).containsExactly("Pessoal");
        assertThat(erro(() -> previa(itau)).codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        // A conta técnica nasceu com o Itaú e o Nubank ainda a usa.
        assertThat(banco.fetchCount(banco.selectFrom("conta").where("dono_id = ? and tipo = 'saldo_abertura' and arquivado_em is null", dono))).isEqualTo(1);
    }

    @Test
    void desfazerOPrimeiroAmbitoNaoImpedeOProximoGasto() {
        estrutura();
        var pessoal = definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, null, null, null));
        confirmar(pessoal.operacao().referencia().id());

        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();

        assertThat(gasto.movimentos()).extracting(LancamentoRegistrado.MovimentoRegistrado::ambito).containsOnly("Pessoal");
    }

    @Test
    void tokenInvalidoOuDeOutroDonoOuComAlvoMudadoEhRecusado() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> primeiro = registrarNoLoteComOperacao("nota", "1.00");
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, null, "nota", null)).valor().confirmacao();

        assertThat(erro(() -> aplicar(null, "nota", "lixo")).codigo()).isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);
        assertThat(erro(() -> aplicar(primeiro.operacao().referencia().id() , null, token + "x")).codigo()).isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);
        Chamador outro = new Chamador(novoDono(), "claude-ai", chamador.escopos());
        assertThat(erro(() -> desfazer.executar(outro, new Desfazer.Entrada(chave(), null, "nota", token))).codigo()).isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);

        registrarNoLote("nota", "2.00");

        assertThat(erro(() -> aplicar(null, "nota", token)).codigo()).isEqualTo(CodigoErro.CONFIRMACAO_INVALIDA);
        assertThat(saldo("Itaú")).isEqualTo("997.00");
    }

    @Test
    void escopoExigidoEhODasOperacoesRevertidas() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("10.00", "Mercado", null, null);
        ResultadoEscrita<ContaDefinida> conta = conta("categoria", "Lazer", null);
        Chamador soEscrita = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write"));
        Chamador soEstrutura = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:structure"));

        ErroTipado semEstrutura = erro(() -> desfazer.executar(soEscrita, new Desfazer.Entrada(null, conta.operacao().referencia().id(), null, null)));
        ErroTipado semEscrita = erro(() -> desfazer.executar(soEstrutura, new Desfazer.Entrada(null, gasto.operacao().referencia().id(), null, null)));

        assertThat(semEstrutura.codigo()).isEqualTo(CodigoErro.INSUFFICIENT_SCOPE);
        assertThat(semEstrutura.mensagem()).contains("fin:structure");
        assertThat(semEscrita.codigo()).isEqualTo(CodigoErro.INSUFFICIENT_SCOPE);
        assertThat(semEscrita.mensagem()).contains("fin:write");
        assertThat(desfazer.executar(soEscrita, new Desfazer.Entrada(null, gasto.operacao().referencia().id(), null, null)).valor().confirmacao()).isNotBlank();

        // Desfazer um desfazer exige o escopo da operação original.
        String desfeito = desfazerDe(conta.operacao().referencia().id()).operacao().referencia().id();
        assertThat(erro(() -> desfazer.executar(soEscrita, new Desfazer.Entrada(null, desfeito, null, null))).codigo()).isEqualTo(CodigoErro.INSUFFICIENT_SCOPE);
    }

    @Test
    void finUndoSupreEscritaEEstruturaESoNoDesfazer() {
        estrutura();
        ResultadoEscrita<LancamentoRegistrado> gasto = gasto("10.00", "Mercado", null, null);
        ResultadoEscrita<ContaDefinida> conta = conta("categoria", "Lazer", null);
        var relatorio = resumirPeriodo.executar(chamador, new ResumirPeriodo.Entrada("2026-10-01", "2026-10-31", null, null, null, null, null, null));
        String operacaoDoRelatorio = banco.fetchOne("select substring(r.codigo from 3) from operacao o join referencia_curta r on r.entidade_id = o.id where o.dono_id = ? and o.capacidade = ?",
                dono, "resumo_periodo").get(0, String.class);
        Chamador soDesfazer = new Chamador(dono, "app-web", Set.of(Desfazer.ESCOPO_PROPRIO));

        assertThat(desfazer.executar(soDesfazer, new Desfazer.Entrada(null, gasto.operacao().referencia().id(), null, null)).valor().confirmacao()).isNotBlank();
        assertThat(desfazer.executar(soDesfazer, new Desfazer.Entrada(null, conta.operacao().referencia().id(), null, null)).valor().confirmacao()).isNotBlank();
        // Desfazer um desfazer também: cobre os mesmos dois escopos.
        String desfeito = desfazerDe(conta.operacao().referencia().id()).operacao().referencia().id();
        assertThat(desfazer.executar(soDesfazer, new Desfazer.Entrada(null, desfeito, null, null)).valor().confirmacao()).isNotBlank();

        // Fora da lista fixa, fin:undo não supre: a regra original continua valendo.
        ErroTipado fora = erro(() -> desfazer.executar(soDesfazer, new Desfazer.Entrada(null, "o-" + operacaoDoRelatorio, null, null)));
        assertThat(fora.codigo()).isEqualTo(CodigoErro.INSUFFICIENT_SCOPE);
        assertThat(fora.mensagem()).contains("fin:read");
        assertThat(relatorio.retrato()).isNotBlank();
    }

    @Test
    void alvoPrecisaSerUmaOperacaoDoDono() {
        estrutura();
        LancamentoRegistrado gasto = gasto("10.00", "Mercado", null, null).valor();

        assertThat(erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, null, null, null))).codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
        assertThat(erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, "o-000000", "x", null))).codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, gasto.id(), null, null))).codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, "o-000000", null, null))).codigo()).isEqualTo(CodigoErro.REFERENCIA_INEXISTENTE);
    }

    // Apoio

    private UUID novoDono() {
        UUID id = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, id).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, id.toString()).execute();
        return id;
    }

    private void estrutura() {
        conta("corrente", "Itaú", "1000.00");
        conta("categoria", "Mercado", null);
        meio("pix", "Pix Itaú", "Itaú");
    }

    private ResultadoEscrita<ContaDefinida> conta(String tipo, String nome, String saldo) {
        return conta(tipo, nome, null, saldo);
    }

    private ResultadoEscrita<ContaDefinida> conta(String tipo, String nome, String pai, String saldo) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, pai, saldo, "2020-01-01", null));
    }

    private ResultadoEscrita<ContaDefinida> alterarConta(String ref, String nome) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, ref, null, nome, null, null, null, null, null, null));
    }

    private ResultadoEscrita<?> meio(String tipo, String nome, String conta) {
        return definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, tipo, nome, null, conta, null, null));
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String valor, String categoria, String nota, String lote) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), lote, valor, "Compra", "Pix Itaú", "conversa", null,
                categoria, null, null, null, nota, null, null, Boolean.TRUE, null));
    }

    private void registrar(String valor, String descricao, String categoria, String data) {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, descricao, "Pix Itaú", "conversa", data,
                categoria, null, null, null, null, null, null, Boolean.TRUE, null));
    }

    private LancamentoRegistrado registrarNoLote(String lote, String valor) {
        return registrarNoLoteComOperacao(lote, valor).valor();
    }

    private ResultadoEscrita<LancamentoRegistrado> registrarNoLoteComOperacao(String lote, String valor) {
        return gasto(valor, "Mercado", null, lote);
    }

    private ResultadoEscrita<CorrecaoRegistrada> corrigir(String acao, String lancamento, Alteracoes alteracoes) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, acao, alteracoes, "lançado errado", null));
    }

    private ResultadoEscrita<ReversaoRegistrada> previa(ResultadoEscrita<?> escrita) {
        return desfazer.executar(chamador, new Desfazer.Entrada(null, escrita.operacao().referencia().id(), null, null));
    }

    private ResultadoEscrita<ReversaoRegistrada> aplicar(String alvo, String lote, String token) {
        return desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, lote, token));
    }

    private ResultadoEscrita<ReversaoRegistrada> desfazerDe(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        return aplicar(alvo, null, token);
    }

    private ReversaoRegistrada confirmar(String alvo) {
        return desfazerDe(alvo).valor();
    }

    private ListarLancamentos.Pagina listar() {
        return listar(false);
    }

    private ListarLancamentos.Pagina listar(boolean anulados) {
        return listar.executar(chamador, new ListarLancamentos.Entrada(null, null, null, null, null, null, null, null, anulados, null));
    }

    private static ListarLancamentos.Entrada filtroCursor(String cursor) {
        return new ListarLancamentos.Entrada(null, null, null, null, "Pix Itaú", null, null, null, null, cursor);
    }

    private static ListarOperacoes.Entrada filtro(String lote, String capacidade, Boolean desfeitas) {
        return new ListarOperacoes.Entrada(lote, null, null, null, capacidade, desfeitas, null);
    }

    private List<String> descricoes(ListarLancamentos.Entrada entrada) {
        return listar.executar(chamador, entrada).lancamentos().stream().map(LancamentoVisto::descricao).toList();
    }

    /** O id curto do saldo de abertura do Itaú. */
    private String abertura() {
        return (String) banco.fetchValue("""
                select r.codigo from referencia_curta r join lancamento l on l.id = r.entidade_id
                where l.dono_id = ? and l.tipo = 'saldo_abertura' and l.descricao = 'Saldo de abertura de Itaú'
                  and not lancamento_anulado(l.id)""", dono);
    }

    private String saldo(String conta) {
        return obterContexto.executar(chamador).contas().stream().filter(c -> c.nome().equals(conta)).findFirst().orElseThrow().saldo();
    }

    private String estado(UUID operacao) {
        return banco.select(OPERACAO.ESTADO).from(OPERACAO).where(OPERACAO.ID.eq(operacao)).fetchSingle(OPERACAO.ESTADO);
    }

    private UUID apontadoPelaNota() {
        return banco.select(CHAVE_FISCAL.LANCAMENTO_ID).from(CHAVE_FISCAL).where(CHAVE_FISCAL.DONO_ID.eq(dono)).fetchSingle(CHAVE_FISCAL.LANCAMENTO_ID);
    }

    private UUID idDe(String codigo) {
        return (UUID) banco.fetchValue("select entidade_id from referencia_curta where codigo = ?", codigo);
    }

    private String chave() {
        return "chave-" + ++chaves;
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
