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
import tools.alicerce.financeiro.aplicacao.Contexto;
import tools.alicerce.financeiro.aplicacao.CorrecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.CreditoVisto;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;
import tools.alicerce.financeiro.aplicacao.FaturaVista;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.ObterFatura;
import tools.alicerce.financeiro.aplicacao.OperacaoVista;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.RegistrarTransferencia;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;
import tools.alicerce.financeiro.aplicacao.TransferenciaRegistrada;

/** Testes de aceite, nos casos de uso, contra o banco. Cartão com fechamento dia 10 e vencimento dia 17. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class CartoesFaturasTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired RegistrarTransferencia transferir;
    @Autowired CorrigirLancamento corrigir;
    @Autowired Desfazer desfazer;
    @Autowired ObterFatura obterFatura;
    @Autowired ObterContexto obterContexto;
    @Autowired ResolverReferencia resolver;
    @Autowired ResumirPeriodo resumir;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
        conta("corrente", "Itaú", "10000.00");
        conta("poupanca", "Reserva", null);
        conta("categoria", "Mercado", null);
        conta("categoria", "Lazer", null);
        conta("fonte_renda", "Salário", null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, true, null, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Mãe", null, null, null, null));
        cartao("Nubank", 10, 17, "5000.00", "Itaú", null, null);
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
        // Toda linha do cartão tem fatura, e todo lançamento soma zero.
        assertThat(banco.fetchValue("""
                select count(*) from movimento m join conta c on c.id = m.conta_id
                where c.dono_id = ? and c.tipo = 'cartao_credito' and m.fatura_id is null""", dono)).isEqualTo(0L);
    }


    @Test
    void criarCartaoCriaOMeioDeCreditoEApareceNoContexto() {
        Contexto contexto = obterContexto.executar(chamador);

        assertThat(contexto.meiosPagamento()).anyMatch(m -> m.nome().equals("Nubank") && m.tipo().equals("credito") && m.padrao());
        Contexto.CartaoContexto cartao = contexto.cartoes().get(0);
        assertThat(cartao.nome()).isEqualTo("Nubank");
        assertThat(cartao.diaFechamento()).isEqualTo(10);
        assertThat(cartao.limiteDisponivel()).isEqualTo("5000.00");
        assertThat(cartao.contaPagamento()).isEqualTo("Itaú");
        assertThat(cartao.faturaAtual().total()).isEqualTo("0.00");
        assertThat(cartao.faturaAtual().id()).isNull();
    }

    @Test
    void cartaoSemDiasPedeOsCamposECreditoSoLiquidaEmCartao() {
        ErroTipado semDias = erro(() -> definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Inter", null,
                null, null, null, null, null)));
        ErroTipado creditoNaCorrente = erro(() -> definirMeio.executar(chamador,
                new DefinirMeioPagamento.Entrada(chave(), null, null, "credito", "X", null, "Itaú", false, null)));
        ErroTipado pixNoCartao = erro(() -> definirMeio.executar(chamador,
                new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Y", null, "Nubank", false, null)));

        assertThat(semDias.camposFaltando()).containsExactly("cartao.dia_fechamento", "cartao.dia_vencimento");
        assertThat(creditoNaCorrente.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(pixNoCartao.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "credito", "Nubank adicional", null, "Nubank", false, null));
    }

    @Test
    void desfazerCriacaoDoCartaoArquivaContaEMeioEAlterarDiasVolta() {
        ContaDefinida criado = cartao("Inter", 5, 12, null, null, null, null).valor();
        String criacao = ultimaOperacao();
        assertThat(criado.cartao().meioPagamento()).isEqualTo("Inter");

        ResultadoEscrita<ContaDefinida> alterado = definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, "Inter", null, null, null,
                null, null, null, null, null, new DadosCartao(20, null, "1000.00", null)));
        assertThat(alterado.valor().cartao().diaFechamento()).isEqualTo(20);
        assertThat(alterado.valor().cartao().limite()).isEqualTo("1000.00");

        confirmar(alterado.operacao().referencia().id());
        Contexto.CartaoContexto voltou = obterContexto.executar(chamador).cartoes().stream().filter(c -> c.nome().equals("Inter")).findFirst().orElseThrow();
        assertThat(voltou.diaFechamento()).isEqualTo(5);
        assertThat(voltou.limite()).isNull();

        ReversaoRegistrada reversao = confirmar(criacao).valor();
        assertThat(reversao.entidadesArquivadas()).extracting(ReversaoRegistrada.EntidadeRevertida::nome).contains("Inter");
        assertThat(obterContexto.executar(chamador).cartoes()).noneMatch(c -> c.nome().equals("Inter"));
    }

    @Test
    void saldoDeAberturaDoCartaoEntraNaProximaFaturaAVencer() {
        cartao("Inter", 10, 17, null, null, "800.00", "2026-09-12");

        FaturaVista fatura = obterFatura.executar(chamador, "Inter", "2026-09");

        assertThat(fatura.total()).isEqualTo("800.00");
        assertThat(fatura.itens()).singleElement().satisfies(i -> {
            assertThat(i.tipo()).isEqualTo("saldo_abertura");
            assertThat(i.valorNaFatura()).isEqualTo("800.00");
        });
    }

    @Test
    void meioDeContaArquivadaAindaMudaDeNome() {
        ResultadoEscrita<ContaDefinida> poupanca = definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "poupanca", "Antiga", null, null,
                null, null, null, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "debito", "Débito antigo", null, "Antiga", false, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, "Débito antigo", null, null, null, null, null, true));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, poupanca.valor().id(), null, null, null, null, null, null, null, true));

        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, "Débito antigo", null, "Débito velho", null, null, null, null));
    }

    @Test
    void repetirAMesmaChamadaDevolveOResultadoComAsSaidasDeCartao() {
        String chaveCompra = chave();
        RegistrarGasto.Entrada entrada = new RegistrarGasto.Entrada(chaveCompra, null, "100.00", "TV", "Nubank", "conversa", "2026-09-05", "Lazer", null, null,
                null, null, null, null, true, null, null, 3, null);
        registrarGasto.executar(chamador, entrada);

        ResultadoEscrita<LancamentoRegistrado> repetida = registrarGasto.executar(chamador, entrada);

        assertThat(repetida.repetido()).isTrue();
        assertThat(repetida.valor().credito().faturas()).hasSize(3);
        assertThat(saldoDoCartao()).isEqualTo("-100.00");

        RegistrarTransferencia.Entrada pagamento = new RegistrarTransferencia.Entrada(chave(), null, "10.00", null, "Nubank", "2026-09-20", "2026-09", null, null, null, null);
        transferir.executar(chamador, pagamento);
        ResultadoEscrita<TransferenciaRegistrada> repetido = transferir.executar(chamador, pagamento);
        assertThat(repetido.repetido()).isTrue();
        assertThat(repetido.valor().fatura().restante()).isEqualTo("23.34");
    }


    @Test
    void compraEntraNaFaturaCertaConformeOFechamento() {
        LancamentoRegistrado noFechamento = gasto("50.00", "2026-09-10", null, null).valor();
        LancamentoRegistrado depois = gasto("70.00", "2026-09-11", null, null).valor();

        assertThat(noFechamento.credito().faturas().get(0).periodo()).isEqualTo("2026-09");
        assertThat(noFechamento.credito().faturas().get(0).venceEm()).isEqualTo("2026-09-17");
        assertThat(noFechamento.credito().faturas().get(0).parcela()).isNull();
        assertThat(depois.credito().faturas().get(0).periodo()).isEqualTo("2026-10");
    }

    @Test
    void cemEmTresVezesParcelasSomamOTotalUmaPorFaturaEOLimiteCaiPeloTotal() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 3, null).valor();

        assertThat(compra.valor()).isEqualTo("100.00");
        CreditoVisto credito = compra.credito();
        assertThat(credito.parcelas()).isEqualTo(3);
        assertThat(credito.limiteDisponivel()).isEqualTo("4900.00");
        assertThat(credito.faturas()).extracting(CreditoVisto.ParcelaVista::periodo).containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(credito.faturas()).extracting(CreditoVisto.ParcelaVista::parcela).containsExactly("1/3", "2/3", "3/3");
        assertThat(credito.faturas()).extracting(CreditoVisto.ParcelaVista::valorDaParcela).containsExactly("33.34", "33.33", "33.33");
        assertThat(saldoDoCartao()).isEqualTo("-100.00");

        ResumoVisto competencia = resumo("2026-09", null);
        assertThat(competencia.saidas()).isEqualTo("100.00");
        assertThat(resumo("2026-10", null).saidas()).isEqualTo("0.00");
    }

    @Test
    void compraEmQuarentaEOitoVezesCriaAsFaturasSobDemanda() {
        LancamentoRegistrado compra = gasto("1000.00", "2026-01-05", 48, null).valor();

        assertThat(compra.credito().faturas()).hasSize(48);
        assertThat(compra.credito().faturas().get(47).periodo()).isEqualTo("2029-12");
        assertThat(banco.fetchValue("select count(*) from fatura where dono_id = ?", dono)).isEqualTo(48L);
    }

    @Test
    void parcelasForaDoCreditoEForaDosLimitesSaoRecusadas() {
        ErroTipado pix = erro(() -> compra("10.00", "Pix", "2026-09-05", 2, null));
        ErroTipado muitas = erro(() -> compra("10.00", "Nubank", "2026-09-05", 49, null));
        ErroTipado jurosAVista = erro(() -> compra("10.00", "Nubank", "2026-09-05", 1, "12.00"));
        ErroTipado jurosMenor = erro(() -> compra("10.00", "Nubank", "2026-09-05", 2, "9.00"));

        assertThat(pix.campo()).isEqualTo("parcelas");
        assertThat(pix.mensagem()).contains("crédito");
        assertThat(muitas.campo()).isEqualTo("parcelas");
        assertThat(jurosAVista.campo()).isEqualTo("total_com_juros");
        assertThat(jurosMenor.campo()).isEqualTo("total_com_juros");
    }

    @Test
    void comJurosOGastoEntraPeloTotalEOPrecoAVistaFicaSoParaConsulta() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 2, "120.00").valor();

        assertThat(compra.valor()).isEqualTo("120.00");
        assertThat(compra.credito().precoAVista()).isEqualTo("100.00");
        assertThat(compra.credito().faturas()).extracting(CreditoVisto.ParcelaVista::valorDaParcela).containsExactly("60.00", "60.00");
        assertThat(resumo("2026-09", null).saidas()).isEqualTo("120.00");
        LancamentoVisto visto = (LancamentoVisto) resolver.resolver(chamador, compra.id());
        assertThat(visto.precoAVista()).isEqualTo("100.00");
        assertThat(visto.parcelas()).isEqualTo(2);
        assertThat(visto.valor()).isEqualTo("120.00");
    }

    @Test
    void compraEmOutraMoedaParceladaUsaOValorEmReais() {
        LancamentoRegistrado compra = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "110.00", "Livro", "Nubank", "conversa",
                "2026-09-05", "Lazer", null, null, null, null, null, null, true, null, new EntradaMoeda("20.00", "USD", null, null), 2, null)).valor();

        assertThat(compra.valor()).isEqualTo("110.00");
        assertThat(compra.original().moedaOriginal()).isEqualTo("USD");
        assertThat(compra.credito().faturas()).extracting(CreditoVisto.ParcelaVista::valorDaParcela).containsExactly("55.00", "55.00");
    }

    @Test
    void simularNaoGravaNemAsFaturas() {
        LancamentoRegistrado simulado = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(null, null, "100.00", "TV", "Nubank", "conversa",
                "2026-09-05", "Lazer", null, null, null, null, null, null, null, true, null, 3, null)).valor();

        assertThat(simulado.credito().faturas()).hasSize(3);
        assertThat(banco.fetchValue("select count(*) from fatura where dono_id = ?", dono)).isEqualTo(0L);
    }

    @Test
    void compraComItensEAmbitosDiferentesFechaEmCadaParcela() {
        LancamentoRegistrado compra = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "100.00", "Feira", "Nubank", "conversa",
                "2026-09-05", null, null, List.of(new RegistrarGasto.Item("Pão", null, "50.01", "Mercado", "Pessoal"),
                        new RegistrarGasto.Item("Cinema", null, "49.99", "Lazer", "Mãe")),
                null, null, null, null, true, null, null, 3, null)).valor();

        assertThat(compra.credito().faturas()).extracting(CreditoVisto.ParcelaVista::valorDaParcela).containsExactly("33.34", "33.33", "33.33");
        FaturaVista setembro = obterFatura.executar(chamador, "Nubank", "2026-09");
        assertThat(setembro.total()).isEqualTo("33.34");
        assertThat(setembro.itens()).singleElement().satisfies(i -> assertThat(i.parcela()).isEqualTo("1/3"));
        assertThat(resumo("2026-09", "caixa").saidas()).isEqualTo("33.34");
        ResumoVisto ano = resumo("2026", "caixa");
        assertThat(ano.saidas()).isEqualTo("100.00");
        assertThat(ano.quebras().get(0).linhas()).extracting(ResumoVisto.LinhaVista::saidas).containsExactly("50.01", "49.99");
    }


    @Test
    void corrigirValorRefazTodasAsParcelas() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 3, null).valor();

        CorrecaoRegistrada correcao = corrige(compra.id(), new Alteracoes("90.00", null, null, null, null, null, null, null, null)).valor();

        assertThat(correcao.faturas()).extracting(FaturaVista::total).containsExactly("30.00", "30.00", "30.00");
        assertThat(saldoDoCartao()).isEqualTo("-90.00");
    }

    @Test
    void corrigirDataMudaDeFaturaEParcelasMudamAQuantidade() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 2, null).valor();

        CorrecaoRegistrada datada = corrige(compra.id(), new Alteracoes(null, null, "2026-09-20", null, null, null, null, null, null)).valor();

        assertThat(obterFatura.executar(chamador, "Nubank", "2026-09").total()).isEqualTo("0.00");
        assertThat(obterFatura.executar(chamador, "Nubank", "2026-11").total()).isEqualTo("50.00");

        CorrecaoRegistrada mudou = corrige(datada.novo(), new Alteracoes(null, null, null, null, null, null, null, null, null, null, 4, null)).valor();
        assertThat(mudou.movimentos()).filteredOn(m -> m.parcela() != null).extracting(LancamentoVisto.MovimentoVisto::parcela)
                .containsExactlyInAnyOrder("1/4", "2/4", "3/4", "4/4");
    }

    @Test
    void trocarOMeioTiraEPoeNoCartao() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 2, null).valor();

        ErroTipado semParcelasCertas = erro(() -> corrige(compra.id(), new Alteracoes(null, null, null, null, null, "Pix", null, null, null)));
        assertThat(semParcelasCertas.campo()).isEqualTo("alteracoes.parcelas");

        CorrecaoRegistrada noPix = corrige(compra.id(), new Alteracoes(null, null, null, null, null, "Pix", null, null, null, null, 1, null)).valor();
        assertThat(saldoDoCartao()).isEqualTo("0.00");

        corrige(noPix.novo(), new Alteracoes(null, null, null, null, null, "Nubank", null, null, null, null, 3, null));
        assertThat(saldoDoCartao()).isEqualTo("-100.00");
        assertThat(obterFatura.executar(chamador, "Nubank", "2026-11").itens()).singleElement().satisfies(i -> assertThat(i.parcela()).isEqualTo("3/3"));
    }

    @Test
    void jurosSeCorrigemSemPerderOPrecoAVista() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 2, "120.00").valor();

        CorrecaoRegistrada preco = corrige(compra.id(), new Alteracoes("110.00", null, null, null, null, null, null, null, null)).valor();
        LancamentoVisto visto = (LancamentoVisto) resolver.resolver(chamador, preco.novo());
        assertThat(visto.precoAVista()).isEqualTo("110.00");
        assertThat(visto.valor()).isEqualTo("120.00");

        CorrecaoRegistrada total = corrige(preco.novo(), new Alteracoes(null, null, null, null, null, null, null, null, null, null, null, "130.00")).valor();
        visto = (LancamentoVisto) resolver.resolver(chamador, total.novo());
        assertThat(visto.valor()).isEqualTo("130.00");
        assertThat(visto.precoAVista()).isEqualTo("110.00");
    }

    @Test
    void anularEDesfazerAlcancamTodasAsParcelas() {
        ResultadoEscrita<LancamentoRegistrado> compra = gasto("100.00", "2026-09-05", 3, null);

        ReversaoRegistrada desfeita = confirmar(compra.operacao().referencia().id()).valor();
        assertThat(saldoDoCartao()).isEqualTo("0.00");
        assertThat(desfeita.estruturaMantida()).contains("fatura");
        assertThat(obterFatura.executar(chamador, "Nubank", "2026-10").itens()).isEmpty();

        String desfazerOperacao = ultimaOperacao();
        confirmar(desfazerOperacao);
        assertThat(saldoDoCartao()).isEqualTo("-100.00");
        assertThat(obterFatura.executar(chamador, "Nubank", "2026-10").total()).isEqualTo("33.33");

        corrige(compra.valor().id(), "anular");
        assertThat(saldoDoCartao()).isEqualTo("0.00");
    }

    @Test
    void compraComFaturaPagaEBloqueadaComADependente() {
        ResultadoEscrita<LancamentoRegistrado> compra = gasto("100.00", "2026-09-05", 3, null);
        ResultadoEscrita<TransferenciaRegistrada> pagamento = pagar("33.34", "2026-09");

        ErroTipado anular = erro(() -> corrige(compra.valor().id(), "anular"));
        assertThat(anular.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(anular.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(pagamento.valor().id());

        ErroTipado corrigirValor = erro(() -> corrige(compra.valor().id(), new Alteracoes("90.00", null, null, null, null, null, null, null, null)));
        assertThat(corrigirValor.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);

        ErroTipado desfazerCompra = erro(() -> desfazer.executar(chamador,
                new Desfazer.Entrada(null, compra.operacao().referencia().id(), null, null)));
        assertThat(desfazerCompra.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(desfazerCompra.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(pagamento.operacao().referencia().id());

        // Desfeito o pagamento, a compra volta a ser alcançável.
        confirmar(pagamento.operacao().referencia().id());
        assertThat(obterFatura.executar(chamador, "Nubank", "2026-09").pago()).isEqualTo("0.00");
        confirmar(compra.operacao().referencia().id());
        assertThat(saldoDoCartao()).isEqualTo("0.00");
    }

    @Test
    void corrigirSoCategoriaOuDescricaoPassaComFaturaPagaEPodeSerDesfeito() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 3, null).valor();
        pagar("33.34", "2026-09");

        ResultadoEscrita<CorrecaoRegistrada> correcao = corrige(compra.id(), new Alteracoes(null, "TV da sala", null, "Mercado", null, null, null, null, null));

        FaturaVista setembro = obterFatura.executar(chamador, "Nubank", "2026-09");
        assertThat(setembro.situacao()).isEqualTo("paga");
        assertThat(setembro.total()).isEqualTo("33.34");
        confirmar(correcao.operacao().referencia().id());
        assertThat(obterFatura.executar(chamador, "Nubank", "2026-09").situacao()).isEqualTo("paga");
    }

    @Test
    void desfazerOLoteComPagamentoECompraFunciona() {
        String lote = "lote-" + UUID.randomUUID();
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), lote, "100.00", "TV", "Nubank", "conversa", "2026-09-05", "Lazer", null, null,
                null, null, null, null, true, null, null, 2, null));
        transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), lote, "50.00", "Itaú", "Nubank", "2026-09-20", "2026-09", null, null, null, null));

        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, null, lote, null)).valor().confirmacao();
        desfazer.executar(chamador, new Desfazer.Entrada(chave(), null, lote, token));

        assertThat(saldoDoCartao()).isEqualTo("0.00");
    }


    @Test
    void transferenciaSimplesMoveOSaldoENaoContaNoResumo() {
        TransferenciaRegistrada t = transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "300.00", "Itaú", "Reserva", "2026-09-05",
                null, null, null, null, null)).valor();

        assertThat(t.tipo()).isEqualTo("transferencia");
        assertThat(t.descricao()).isEqualTo("Transferência de Itaú para Reserva");
        assertThat(t.saldos()).extracting(s -> s.conta() + " " + s.saldo()).containsExactlyInAnyOrder("Itaú 9700.00", "Reserva 300.00");
        for (String visao : List.of("competencia", "caixa")) {
            ResumoVisto r = resumo("2026-09", visao);
            assertThat(r.entradas()).isEqualTo("0.00");
            assertThat(r.saidas()).isEqualTo("0.00");
        }
        assertThat(ultimaOperacaoVista().resumo()).isEqualTo("transferência de R$ 300,00, de Itaú para Reserva");
    }

    @Test
    void transferenciaRecusaOrigemCartaoMesmaContaEFaturaSemCartao() {
        assertThat(erro(() -> transfere("10.00", "Nubank", "Itaú", null)).campo()).isEqualTo("de");
        assertThat(erro(() -> transfere("10.00", "Itaú", "Itaú", null)).campo()).isEqualTo("ate");
        assertThat(erro(() -> transfere("10.00", "Itaú", "Reserva", "2026-09")).campo()).isEqualTo("fatura");
        assertThat(erro(() -> transfere(null, "Itaú", null, null)).camposFaltando()).containsExactly("valor", "ate");
    }

    @Test
    void pagamentoParcialDeixaAFaturaParcialmentePagaEADiferencaDevida() {
        gasto("100.00", "2026-09-05", null, null);

        TransferenciaRegistrada parcial = pagar("40.00", null).valor();

        assertThat(parcial.tipo()).isEqualTo("pagamento_fatura");
        assertThat(parcial.fatura().situacao()).isEqualTo("parcialmente_paga");
        assertThat(parcial.fatura().pago()).isEqualTo("40.00");
        assertThat(parcial.fatura().restante()).isEqualTo("60.00");
        assertThat(saldoDoCartao()).isEqualTo("-60.00");
        assertThat(parcial.de()).isEqualTo("Itaú");

        // Sem fatura informada, o restante continua sendo a fatura fechada mais antiga.
        TransferenciaRegistrada resto = pagar("60.00", null).valor();
        assertThat(resto.fatura().situacao()).isEqualTo("paga");
        assertThat(resto.fatura().restante()).isEqualTo("0.00");

        assertThat(erro(() -> pagar("1.00", null)).campo()).isEqualTo("fatura");
    }

    @Test
    void pagamentoAcimaDoRestanteERecusadoEOPagamentoNaoCorrigeSoAnula() {
        gasto("100.00", "2026-09-05", null, null);

        ErroTipado acima = erro(() -> pagar("100.01", "2026-09"));
        assertThat(acima.campo()).isEqualTo("valor");
        assertThat(acima.mensagem()).contains("100.00");

        TransferenciaRegistrada pago = pagar("100.00", "2026-09").valor();
        assertThat(erro(() -> corrige(pago.id(), new Alteracoes("90.00", null, null, null, null, null, null, null, null))).campo()).isEqualTo("acao");

        corrige(pago.id(), "anular");
        FaturaVista fatura = obterFatura.executar(chamador, "Nubank", "2026-09");
        assertThat(fatura.situacao()).isEqualTo("fechada");
        assertThat(fatura.pagamentos()).isEmpty();
        assertThat(saldoDoCartao()).isEqualTo("-100.00");
    }

    @Test
    void desfazerPagamentoVoltaASituacaoSozinho() {
        gasto("100.00", "2026-09-05", null, null);
        ResultadoEscrita<TransferenciaRegistrada> pagamento = pagar("100.00", "2026-09");

        confirmar(pagamento.operacao().referencia().id());

        assertThat(obterFatura.executar(chamador, "Nubank", "2026-09").situacao()).isEqualTo("fechada");
    }

    @Test
    void pagarAdiantadoExigeInformarAFatura() {
        gasto("100.00", "2099-09-05", null, null);

        assertThat(erro(() -> pagar("10.00", null)).campo()).isEqualTo("fatura");
        assertThat(pagar("10.00", "2099-09").valor().fatura().situacao()).isEqualTo("parcialmente_paga");
    }


    @Test
    void obterFaturaTrazParcelasMarcadasEPagamentos() {
        gasto("90.00", "2026-08-05", 3, null);
        gasto("20.00", "2026-09-05", null, null);
        pagar("10.00", "2026-09");

        FaturaVista fatura = obterFatura.executar(chamador, "Nubank", "2026-09");

        assertThat(fatura.total()).isEqualTo("50.00");
        assertThat(fatura.restante()).isEqualTo("40.00");
        assertThat(fatura.fechaEm()).isEqualTo("2026-09-10");
        assertThat(fatura.venceEm()).isEqualTo("2026-09-17");
        assertThat(fatura.itens()).extracting(i -> i.valorNaFatura() + " " + i.parcela()).containsExactly("30.00 2/3", "20.00 null");
        assertThat(fatura.pagamentos()).singleElement().satisfies(p -> assertThat(p.valor()).isEqualTo("10.00"));
    }

    @Test
    void resolverReferenciaAbreAFaturaPeloId() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 2, null).valor();
        String id = compra.credito().faturas().get(1).fatura();

        FaturaVista fatura = (FaturaVista) resolver.resolver(chamador, id);

        assertThat(id).startsWith("f-");
        assertThat(fatura.id()).isEqualTo(id);
        assertThat(fatura.periodo()).isEqualTo("2026-10");
        assertThat(obterFatura.executar(chamador, "Nubank", id).periodo()).isEqualTo("2026-10");
    }

    @Test
    void faturaInexistenteEReferenciaDeOutroDonoNaoAparecem() {
        String id = gasto("100.00", "2026-09-05", null, null).valor().credito().faturas().get(0).fatura();
        UUID outro = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, outro).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, outro.toString()).execute();
        Chamador intruso = new Chamador(outro, "claude-ai", Set.of("fin:read"));

        assertThat(erro(() -> obterFatura.executar(chamador, "Nubank", "2031-01")).codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
        assertThat(erro(() -> resolver.resolver(intruso, id)).codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
    }


    @Test
    void competenciaECaixaDiferemNaCompraParceladaEPagamentoNaoConta() {
        gasto("100.00", "2026-09-05", 3, null);
        pixMercado("10.00", "2026-09-06");
        ganho("500.00", "2026-09-06");
        pagar("33.34", "2026-09");

        ResumoVisto competencia = resumo("2026-09", "competencia");
        ResumoVisto caixa = resumo("2026-09", "caixa");
        ResumoVisto outubro = resumo("2026-10", "caixa");

        assertThat(competencia.saidas()).isEqualTo("110.00");
        assertThat(competencia.avisoVisao()).isNull();
        assertThat(caixa.saidas()).isEqualTo("43.34");
        assertThat(caixa.entradas()).isEqualTo("500.00");
        assertThat(caixa.avisoVisao()).contains("vencimento");
        assertThat(outubro.saidas()).isEqualTo("33.33");
        assertThat(outubro.periodoAnterior().saidas()).isEqualTo("43.34");
        assertThat(resumo("2026-11", "caixa").saidas()).isEqualTo("33.33");
    }

    @Test
    void caixaSegueCorrecaoEAnulacao() {
        LancamentoRegistrado compra = gasto("100.00", "2026-09-05", 2, null).valor();
        CorrecaoRegistrada correcao = corrige(compra.id(), new Alteracoes("80.00", null, null, null, null, null, null, null, null)).valor();
        assertThat(resumo("2026-10", "caixa").saidas()).isEqualTo("40.00");

        corrige(correcao.novo(), "anular");

        assertThat(resumo("2026-10", "caixa").saidas()).isEqualTo("0.00");
    }

    @Test
    void caixaQuebraPorContaMostraOCartaoEFiltraAmbito() {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "100.00", "TV", "Nubank", "conversa", "2026-09-05", "Lazer", "Mãe", null,
                null, null, null, null, true, null, null, 2, null));

        ResumoVisto mae = resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, "2026-09", List.of("conta", "categoria"), "Mãe", null, "caixa", false)).resumo();
        ResumoVisto pessoal = resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, "2026-09", null, "Pessoal", null, "caixa", false)).resumo();

        assertThat(mae.saidas()).isEqualTo("50.00");
        assertThat(mae.quebras().get(0).linhas()).extracting(l -> l.nome() + " " + l.saidas()).containsExactly("Nubank 50.00");
        assertThat(mae.quebras().get(1).linhas()).extracting(l -> l.nome() + " " + l.saidas()).containsExactly("Lazer 50.00");
        assertThat(pessoal.saidas()).isEqualTo("0.00");
    }

    private void conta(String tipo, String nome, String saldo) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, "2020-01-01", null));
    }

    private ResultadoEscrita<ContaDefinida> cartao(String nome, int fechamento, int vencimento, String limite, String contaPagamento, String saldo, String data) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", nome, null, null, null, saldo, data == null ? "2020-01-01" : data, null,
                new DadosCartao(fechamento, vencimento, limite, contaPagamento)));
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String valor, String data, Integer parcelas, String juros) {
        return compra(valor, "Nubank", data, parcelas, juros);
    }

    private ResultadoEscrita<LancamentoRegistrado> compra(String valor, String meio, String data, Integer parcelas, String juros) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra " + valor, meio, "conversa", data, "Lazer", null, null,
                null, null, null, null, true, null, null, parcelas, juros));
    }

    private void pixMercado(String valor, String data) {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Pão", "Pix", "conversa", data, "Mercado", null, null, null, null, null,
                null, true, null));
    }

    private void ganho(String valor, String data) {
        registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, valor, "Salário", "Itaú", "Salário", null, data, null, null, null, null, null,
                true, null));
    }

    private ResultadoEscrita<TransferenciaRegistrada> pagar(String valor, String fatura) {
        return transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, valor, null, "Nubank", data(), fatura, null, null,
                true, null));
    }

    private static String data() {
        return "2026-09-20";
    }

    private ResultadoEscrita<TransferenciaRegistrada> transfere(String valor, String de, String ate, String fatura) {
        return transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, valor, de, ate, "2026-09-05", fatura, null, null, null, null));
    }

    private ResultadoEscrita<CorrecaoRegistrada> corrige(String lancamento, Alteracoes alteracoes) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, "corrigir", alteracoes, "Errei", null));
    }

    private ResultadoEscrita<CorrecaoRegistrada> corrige(String lancamento, String acao) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, acao, null, "Errei", null));
    }

    private ResultadoEscrita<ReversaoRegistrada> confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        return desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private ResumoVisto resumo(String periodo, String visao) {
        return resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, periodo, null, null, null, visao, null)).resumo();
    }

    private String saldoDoCartao() {
        return obterContexto.executar(chamador).contas().stream().filter(c -> c.nome().equals("Nubank")).findFirst().orElseThrow().saldo();
    }

    private String ultimaOperacao() {
        return ultimaOperacaoVista().id();
    }

    private OperacaoVista ultimaOperacaoVista() {
        return listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, null, null, null)).operacoes().stream()
                .filter(o -> !o.capacidade().equals("resumo_periodo")).findFirst().orElseThrow();
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
