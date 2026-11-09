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
import tools.alicerce.financeiro.aplicacao.ContaDefinida;
import tools.alicerce.financeiro.aplicacao.Contexto.ContaContexto;
import tools.alicerce.financeiro.aplicacao.CorrecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto;
import tools.alicerce.financeiro.aplicacao.ListarLancamentos;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.OriginalVisto;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.RegistrarTransferencia;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoRegistrado;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.SaldosVistos;
import tools.alicerce.financeiro.aplicacao.RetratoVisto;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;
import tools.alicerce.financeiro.aplicacao.SaldoConta;
import tools.alicerce.financeiro.aplicacao.TransferenciaRegistrada;

/** Testes de aceite, nos casos de uso, contra o banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class ContasMoedaEstrangeiraTest {

    private static final String DATA = "2026-10-20";

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired RegistrarTransferencia transferir;
    @Autowired CorrigirLancamento corrigir;
    @Autowired Desfazer desfazer;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarLancamentos listar;
    @Autowired ObterContexto contexto;
    @Autowired ResumirPeriodo resumo;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
        conta("corrente", "Itaú", "10000.00", null);
        conta("categoria", "Assinaturas", null, null);
        conta("fonte_renda", "Consultoria", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
        assertThat(banco.fetch("""
                select l.id, m.moeda from lancamento l join movimento m on m.lancamento_id = l.id
                where l.dono_id = ? group by l.id, m.moeda having sum(m.valor_centavos) <> 0""", dono)).isEmpty();
    }

    @Test
    void contaEmUsdNasceComAberturaESaldoEmUsd() {
        ContaDefinida conta = conta("corrente", "Wise", "1000.00", "usd").valor();

        assertThat(conta.moeda()).isEqualTo("USD");
        assertThat(conta.saldo()).isEqualTo("1000.00");
        ContaContexto vista = contaNoContexto("Wise");
        assertThat(vista.moeda()).isEqualTo("USD");
        assertThat(vista.saldo()).isEqualTo("1000.00");
        assertThat(contaNoContexto("Itaú").moeda()).isEqualTo("BRL");
        assertThat(banco.fetchSingle("select m.moeda from movimento m join conta c on c.id = m.conta_id where c.dono_id = ? and c.nome = 'Wise'", dono).get(0)).isEqualTo("USD");
        assertThat(nomesTecnicos()).containsExactlyInAnyOrder("Saldo de abertura", "Saldo de abertura USD");
    }

    @Test
    void soContaDeDinheiroEMoedaDeDuasCasas() {
        for (String moeda : List.of("JPY", "BHD", "XAU", "ZZZ")) {
            assertThat(erro(() -> conta("corrente", "Conta " + moeda, null, moeda)).codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        }
        assertThat(erro(() -> conta("categoria", "Viagem", null, "USD")).mensagem()).contains("Só contas de dinheiro");
        assertThat(erro(() -> conta("emprestimo", "Dívida", null, "USD")).mensagem()).contains("Só contas de dinheiro");
        assertThat(erro(() -> definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Cartão USD", null, null, null, null,
                null, null, new DadosCartao(10, 17, null, null), "USD"))).mensagem()).contains("Só contas de dinheiro");
        assertThat(conta("categoria", "Mercado", null, "BRL").valor().moeda()).isEqualTo("BRL");
    }

    @Test
    void moedaMudaSoSemMovimentos() {
        ContaDefinida conta = conta("corrente", "Wise", null, "USD").valor();

        assertThat(mudarMoeda(conta.id(), "EUR").valor().moeda()).isEqualTo("EUR");
        assertThat(mudarMoeda(conta.id(), "USD").valor().moeda()).isEqualTo("USD");
        var cambio = cambio("5500.00", null, "5.50", null);
        ErroTipado erro = erro(() -> mudarMoeda(conta.id(), "EUR"));
        assertThat(erro.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(erro.mensagem()).contains("não muda");

        // Anulado ou não, o movimento continua no livro-razão e prende a moeda.
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, cambio.valor().id(), "anular", null, "erro", null));
        assertThat(erro(() -> mudarMoeda(conta.id(), "EUR")).codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        // Repetir a moeda atual não é mudança.
        assertThat(mudarMoeda(conta.id(), "USD").valor().moeda()).isEqualTo("USD");
    }

    @Test
    void desfazerAMudancaDeMoedaRestauraEFicaBloqueadoComMovimentos() {
        ContaDefinida conta = conta("corrente", "Wise", null, "EUR").valor();
        var mudanca = mudarMoeda(conta.id(), "USD");
        confirmar(mudanca.operacao().referencia().id());
        assertThat(contaNoContexto("Wise").moeda()).isEqualTo("EUR");

        var deNovo = mudarMoeda(conta.id(), "USD");
        cambio("5500.00", null, "5.50", null);
        ErroTipado erro = erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, deNovo.operacao().referencia().id(), null, null)));
        assertThat(erro.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(contaNoContexto("Wise").moeda()).isEqualTo("USD");
    }

    @Test
    void transferenciaDeBrlParaUsdGuardaACotacaoEFechaCadaMoeda() {
        conta("corrente", "Wise", null, "USD");

        TransferenciaRegistrada t = cambio("5500.00", null, "5.50", null).valor();

        assertThat(t.valor()).isEqualTo("5500.00");
        assertThat(t.moeda()).isEqualTo("BRL");
        assertThat(t.original()).isEqualTo(new OriginalVisto("1000.00", "USD", "5.5", DATA));
        assertThat(t.saldos()).containsExactly(new SaldoConta("Itaú", "4500.00", "BRL"), new SaldoConta("Wise", "1000.00", "USD"));
        assertThat(nomesTecnicos()).contains("Conversão BRL", "Conversão USD");
        assertThat(banco.fetchSingle("""
                select l.cotacao, l.moeda_original, l.valor_original_minimo from lancamento l join referencia_curta r on r.entidade_id = l.id where r.codigo = ?""", t.id())
                .intoList()).containsExactly(new java.math.BigDecimal("5.5000000000"), "USD", 100000L);

        LancamentoVisto lido = resolver.executar(chamador, t.id());
        assertThat(lido.valor()).isEqualTo("5500.00");
        assertThat(lido.moeda()).isEqualTo("BRL");
        assertThat(lido.movimentos()).extracting(LancamentoVisto.MovimentoVisto::conta, LancamentoVisto.MovimentoVisto::valor, LancamentoVisto.MovimentoVisto::moeda)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("Itaú", "-5500.00", "BRL"), org.assertj.core.groups.Tuple.tuple("Conversão BRL", "5500.00", "BRL"),
                        org.assertj.core.groups.Tuple.tuple("Conversão USD", "-1000.00", "USD"), org.assertj.core.groups.Tuple.tuple("Wise", "1000.00", "USD"));
    }

    @Test
    void transferenciaDeUsdParaBrlMantemValorNaMoedaDoDono() {
        conta("corrente", "Wise", "1000.00", "USD");

        TransferenciaRegistrada t = transferencia(null, "Wise", "Itaú", "100.00", "5.4567", "2026-10-19").valor();

        assertThat(t.valor()).isEqualTo("545.67");
        assertThat(t.moeda()).isEqualTo("BRL");
        assertThat(t.original()).isEqualTo(new OriginalVisto("100.00", "USD", "5.4567", "2026-10-19"));
        assertThat(t.saldos()).containsExactly(new SaldoConta("Itaú", "10545.67", "BRL"), new SaldoConta("Wise", "900.00", "USD"));
    }

    @Test
    void doisDosTresBastamETresIncoerentesSaoRecusados() {
        conta("corrente", "Wise", null, "USD");

        TransferenciaRegistrada t = cambio("110.40", "20.00", null, null).valor();
        assertThat(t.original().cotacao()).isEqualTo("5.52");

        assertThat(erro(() -> cambio("500.00", "20.00", "5.52", null)).mensagem()).contains("incoerentes");
    }

    @Test
    void misturaDeMoedasSemConversaoERecusada() {
        conta("corrente", "Wise", null, "USD");

        ErroTipado semCotacao = erro(() -> transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "100.00", "Itaú", "Wise", DATA, null, null, null, null, null)));
        assertThat(semCotacao.codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
        assertThat(semCotacao.camposFaltando()).containsExactly("valor_original", "cotacao");
        assertThat(banco.fetchCount(banco.selectFrom("movimento m join conta c on c.id = m.conta_id").where("c.dono_id = ? and c.nome = 'Wise'", dono))).isZero();
    }

    @Test
    void entreContasDaMesmaMoedaEstrangeiraNaoHaCotacao() {
        conta("corrente", "Wise", "1000.00", "USD");
        conta("corrente", "Nomad", null, "USD");

        TransferenciaRegistrada t = transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "100.00", "Wise", "Nomad", DATA, null, null, null, null, null)).valor();

        assertThat(t.moeda()).isEqualTo("USD");
        assertThat(t.original()).isNull();
        assertThat(t.saldos()).extracting(SaldoConta::moeda).containsOnly("USD");
        assertThat(erro(() -> transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "100.00", "Wise", "Nomad", DATA, null, null, null, null, null,
                new EntradaMoeda(null, null, "5.5", null)))).mensagem()).contains("moedas diferentes");

        CorrecaoRegistrada corrigida = corrigir(t.id(), new Alteracoes("150.00", null, null, null, null, null, null, null, null)).valor();
        assertThat(corrigida.saldos()).containsExactlyInAnyOrder(new SaldoConta("Wise", "850.00", "USD"), new SaldoConta("Nomad", "150.00", "USD"));
    }

    @Test
    void aberturaEmUsdSeCorrigeEmUsd() {
        ContaDefinida conta = conta("corrente", "Wise", "1000.00", "USD").valor();

        CorrecaoRegistrada c = corrigir(conta.lancamentoAbertura(), new Alteracoes("900.00", null, null, null, null, null, null, null, null)).valor();

        assertThat(c.saldos()).containsExactly(new SaldoConta("Wise", "900.00", "USD"));
    }

    @Test
    void cambioEntreDuasEstrangeirasEPagamentoDeFaturaSaoRecusados() {
        conta("corrente", "Wise", "1000.00", "USD");
        conta("corrente", "Revolut", null, "EUR");
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Nubank", null, null, null, "100.00", "2026-10-01", null,
                new DadosCartao(10, 17, null, null)));

        assertThat(erro(() -> transferencia("100.00", "Wise", "Revolut", null, "0.9", null)).mensagem()).contains("duas moedas estrangeiras");
        assertThat(erro(() -> transferencia("100.00", "Wise", "Nubank", null, "5.5", null)).mensagem()).contains("mesma moeda do cartão");
    }

    @Test
    void gastoEGanhoDiretosEmContaEstrangeiraSaoRecusados() {
        conta("corrente", "Wise", "1000.00", "USD");
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "debito", "Débito Wise", null, "Wise", null, null));
        var gasto = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "10.00", "Assinatura", "Pix", "conversa", DATA, "Assinaturas", null, null, null, null, null, null, null, null));
        var ganho = ganho("Itaú");

        ErroTipado semGasto = erro(() -> registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "10.00", "Almoço", "Débito Wise", "conversa", DATA, "Assinaturas", null, null, null, null, null, null, null, null)));
        assertThat(semGasto.campo()).isEqualTo("meio_pagamento");
        assertThat(semGasto.mensagem()).contains("está em USD", "transferências");
        assertThat(erro(() -> ganho("Wise")).campo()).isEqualTo("conta");

        assertThat(erro(() -> corrigir(gasto.valor().id(), new Alteracoes(null, null, null, null, null, "Débito Wise", null, null, null))).campo()).isEqualTo("alteracoes.meio_pagamento");
        assertThat(erro(() -> corrigir(ganho.valor().id(), new Alteracoes(null, null, null, null, null, null, "Wise", null, null))).campo()).isEqualTo("alteracoes.conta");
        assertThat(contaNoContexto("Wise").saldo()).isEqualTo("1000.00");
    }

    @Test
    void anularEDesfazerInvertemAsDuasMoedas() {
        conta("corrente", "Wise", null, "USD");
        var cambio = cambio("5500.00", null, "5.50", null);

        var anulacao = corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, cambio.valor().id(), "anular", null, "erro", null));
        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("10000.00");
        assertThat(contaNoContexto("Wise").saldo()).isEqualTo("0.00");
        LancamentoVisto estorno = resolver.executar(chamador, anulacao.valor().estorno());
        assertThat(estorno.valor()).isEqualTo("5500.00");
        assertThat(estorno.moeda()).isEqualTo("BRL");
        assertThat(estorno.movimentos()).hasSize(4);

        confirmar(anulacao.operacao().referencia().id());
        assertThat(contaNoContexto("Wise").saldo()).isEqualTo("1000.00");

        ResultadoEscrita<ReversaoRegistrada> desfeita = confirmar(cambio.operacao().referencia().id());
        assertThat(desfeita.valor().saldos()).contains(new SaldoConta("Wise", "0.00", "USD"));
        assertThat(desfeita.valor().lancamentosEstornados().get(0).moeda()).isEqualTo("BRL");
        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("10000.00");
        assertThat(desfeita.valor().estruturaMantida()).contains("Conversão BRL", "Conversão USD");

        confirmar(desfeita.operacao().referencia().id());
        assertThat(contaNoContexto("Wise").saldo()).isEqualTo("1000.00");
        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("4500.00");
    }

    @Test
    void corrigirCambioRefazOsDoisLados() {
        conta("corrente", "Wise", null, "USD");
        var cambio = cambio("5500.00", null, "5.50", null);

        // Só a cotação recalcula o valor; só um dos lados mantém o outro e recalcula a cotação, porque
        // os dois lados de um câmbio são o que o extrato mostra.
        CorrecaoRegistrada c = corrigir(cambio.valor().id(), moeda(null, null, "5.60")).valor();
        assertThat(c.saldos()).containsExactlyInAnyOrder(new SaldoConta("Itaú", "4400.00", "BRL"), new SaldoConta("Wise", "1000.00", "USD"));
        assertThat(c.original()).isEqualTo(new OriginalVisto("1000.00", "USD", "5.6", DATA));

        CorrecaoRegistrada d = corrigir(c.novo(), new Alteracoes("5700.00", null, null, null, null, null, null, null, null)).valor();
        assertThat(d.saldos()).contains(new SaldoConta("Itaú", "4300.00", "BRL"));
        assertThat(d.original().cotacao()).isEqualTo("5.7");

        CorrecaoRegistrada e = corrigir(d.novo(), moeda("1050.00", null, null)).valor();
        assertThat(e.saldos()).containsExactlyInAnyOrder(new SaldoConta("Itaú", "4300.00", "BRL"), new SaldoConta("Wise", "1050.00", "USD"));
        assertThat(e.original()).isEqualTo(new OriginalVisto("1050.00", "USD", "5.4285714286", DATA));

        assertThat(erro(() -> corrigir(e.novo(), moeda(null, "EUR", null))).mensagem()).contains("não muda");
        LancamentoVisto vigente = resolver.executar(chamador, e.novo());
        assertThat(vigente.valor()).isEqualTo("5700.00");
        assertThat(vigente.movimentos()).extracting(LancamentoVisto.MovimentoVisto::moeda).containsOnly("BRL", "USD");

        // Trocar só a descrição não mexe em nenhum dos lados.
        CorrecaoRegistrada f = corrigir(e.novo(), new Alteracoes(null, "Remessa", null, null, null, null, null, null, null)).valor();
        assertThat(f.saldos()).containsExactlyInAnyOrder(new SaldoConta("Itaú", "4300.00", "BRL"), new SaldoConta("Wise", "1050.00", "USD"));
    }

    @Test
    void simularCambioNaoGravaNada() {
        conta("corrente", "Wise", null, "USD");

        TransferenciaRegistrada t = transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "5500.00", "Itaú", "Wise", DATA, null, null, null, null, true,
                new EntradaMoeda(null, null, "5.50", null))).valor();

        assertThat(t.id()).isNull();
        assertThat(t.original().valorOriginal()).isEqualTo("1000.00");
        assertThat(nomesTecnicos()).doesNotContain("Conversão BRL", "Conversão USD");
        assertThat(contaNoContexto("Wise").saldo()).isEqualTo("0.00");
    }

    @Test
    void listagemFiltraPeloValorDoLancamentoESemSomarMoedas() {
        conta("corrente", "Wise", null, "USD");
        cambio("5500.00", null, "5.50", null);

        // Somar BRL com USD daria 6.500,00.
        assertThat(listar.executar(chamador, new ListarLancamentos.Entrada(null, null, "Wise", null, null, null, "5500.00", "5500.00", null, null)).lancamentos()).hasSize(1);
        assertThat(listar.executar(chamador, new ListarLancamentos.Entrada(null, null, "Wise", null, null, null, "6500.00", null, null, null)).lancamentos()).isEmpty();
    }

    @Test
    void resumoSemContaEstrangeiraFicaComoSempreFoi() {
        assertThat(resumir("2026-10").saldos()).isNull();
    }

    @Test
    void resumoConverteOsSaldosComACotacaoEADataExplicitas() {
        conta("corrente", "Wise", null, "USD");
        var cambio = cambio("5500.00", null, "5.50", null);

        ResumoVisto r = resumir("2026-10");

        SaldosVistos s = r.saldos();
        assertThat(s.data()).isEqualTo("2026-10-31");
        assertThat(s.moeda()).isEqualTo("BRL");
        assertThat(s.saldoMoedaDono()).isEqualTo("4500.00");
        assertThat(s.saldoConvertido()).isEqualTo("5500.00");
        assertThat(s.total()).isEqualTo("10000.00");
        assertThat(s.avisoSemCotacao()).isNull();
        assertThat(s.contas()).singleElement().satisfies(c -> {
            assertThat(c.nome()).isEqualTo("Wise");
            assertThat(c.moeda()).isEqualTo("USD");
            assertThat(c.saldo()).isEqualTo("1000.00");
            assertThat(c.cotacao()).isEqualTo("5.5");
            assertThat(c.dataCotacao()).isEqualTo(DATA);
            assertThat(c.lancamentoCotacao()).isEqualTo(cambio.valor().id());
            assertThat(c.saldoConvertido()).isEqualTo("5500.00");
            assertThat(c.moedaSaldoConvertido()).isEqualTo("BRL");
        });
        // Câmbio é transferência: não é entrada nem saída.
        assertThat(r.entradas()).isEqualTo("0.00");
        assertThat(r.saidas()).isEqualTo("0.00");
    }

    @Test
    void semNenhumaCotacaoOSaldoFicaForaDoTotalEASaidaDizIsso() {
        conta("corrente", "Wise", "1000.00", "USD");

        SaldosVistos s = resumir("2026-11").saldos();

        assertThat(s.saldoConvertido()).isEqualTo("0.00");
        assertThat(s.total()).isEqualTo("10000.00");
        assertThat(s.avisoSemCotacao()).contains("Wise (USD 1000.00)", "nunca some");
        assertThat(s.contas().get(0).saldoConvertido()).isNull();
        assertThat(s.contas().get(0).moedaSaldoConvertido()).isNull();
    }

    @Test
    void vigoraAUltimaCotacaoVigenteAteOFimDoPeriodoECompraEmMoedaOriginalConta() {
        conta("corrente", "Wise", null, "USD");
        cambio("5500.00", null, "5.50", null);
        var nova = transferencia("6000.00", "Itaú", "Wise", null, "6.00", null);
        assertThat(resumir("2026-10").saldos().contas().get(0).cotacao()).isEqualTo("6");

        // A cotação de um lançamento anulado deixa de valer.
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, nova.valor().id(), "anular", null, "erro", null));
        assertThat(resumir("2026-10").saldos().contas().get(0).cotacao()).isEqualTo("5.5");

        // Compra com moeda original também é cotação que o dono registrou.
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "57.00", "Assinatura", "Pix", "conversa", "2026-11-05", "Assinaturas", null, null, null, null,
                null, null, null, null, new EntradaMoeda("10.00", "USD", null, null)));
        assertThat(resumir("2026-10").saldos().contas().get(0).cotacao()).isEqualTo("5.5");
        var novembro = resumir("2026-11").saldos();
        assertThat(novembro.contas().get(0).cotacao()).isEqualTo("5.7");
        assertThat(novembro.contas().get(0).dataCotacao()).isEqualTo("2026-11-05");

        // O saldo é o do fim do período pedido.
        assertThat(resumir("2026-09").saldos().contas().get(0).saldo()).isEqualTo("0.00");
    }

    @Test
    void oRetratoGuardaAConversaoEAComparacaoMostraAMudanca() {
        conta("corrente", "Wise", null, "USD");
        cambio("5500.00", null, "5.50", null);
        ResumoRegistrado r = resumo.executar(chamador, new ResumirPeriodo.Entrada(null, null, "2026-10", null, null, null, null, null));

        transferencia("6000.00", "Itaú", "Wise", null, "6.00", null);

        RetratoVisto aberto = (RetratoVisto) resolver.resolver(chamador, r.retrato(), true);
        assertThat(aberto.resultado().get("saldos").get("contas").get(0).get("cotacao").asString()).isEqualTo("5.5");
        assertThat(aberto.resultado().get("saldos").get("contas").get(0).get("moeda_saldo_convertido").asString()).isEqualTo("BRL");
        assertThat(aberto.comparacao().mudou()).isTrue();
        assertThat(aberto.comparacao().totalConvertido().retrato()).isEqualTo("10000.00");
        assertThat(aberto.comparacao().totalConvertido().agora()).isEqualTo("10500.00");
    }

    private ResumoVisto resumir(String periodo) {
        return resumo.calcularAgora(chamador, new ResumirPeriodo.Entrada(null, null, periodo, null, null, null, null, null));
    }

    private ResultadoEscrita<ContaDefinida> conta(String tipo, String nome, String abertura, String moeda) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, abertura, "2020-01-01", null, null, moeda));
    }

    private ResultadoEscrita<ContaDefinida> mudarMoeda(String conta, String moeda) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, conta, null, null, null, null, null, null, null, null, null, moeda));
    }

    private ResultadoEscrita<TransferenciaRegistrada> cambio(String valor, String valorOriginal, String cotacao, String dataCotacao) {
        return transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, valor, "Itaú", "Wise", DATA, null, null, null, true, null,
                new EntradaMoeda(valorOriginal, null, cotacao, dataCotacao)));
    }

    /** {@code valorEstrangeiro}, ao vender a moeda; {@code valor}, ao comprar. */
    private ResultadoEscrita<TransferenciaRegistrada> transferencia(String valor, String de, String ate, String valorEstrangeiro, String cotacao, String dataCotacao) {
        return transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, valor, de, ate, DATA, null, null, null, true, null,
                new EntradaMoeda(valorEstrangeiro, null, cotacao, dataCotacao)));
    }

    private ResultadoEscrita<tools.alicerce.financeiro.aplicacao.LancamentoRegistrado> ganho(String conta) {
        return registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, "100.00", "Projeto", conta, "Consultoria", null, DATA, null, null, null, null, null, null, null));
    }

    private ResultadoEscrita<CorrecaoRegistrada> corrigir(String lancamento, Alteracoes alteracoes) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, "corrigir", alteracoes, "acerto", null));
    }

    private static Alteracoes moeda(String valorOriginal, String moeda, String cotacao) {
        return new Alteracoes(null, null, null, null, null, null, null, null, null, new EntradaMoeda(valorOriginal, moeda, cotacao, null));
    }

    private ResultadoEscrita<ReversaoRegistrada> confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        return desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private ContaContexto contaNoContexto(String nome) {
        return contexto.executar(chamador).contas().stream().filter(c -> c.nome().equals(nome)).findFirst().orElseThrow();
    }

    private List<String> nomesTecnicos() {
        return banco.fetch("select nome from conta where dono_id = ? and classe = 'patrimonio'", dono).getValues(0, String.class);
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
