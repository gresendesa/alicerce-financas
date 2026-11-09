package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.ContaDefinida;
import tools.alicerce.financeiro.aplicacao.CorrecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.CreditoVisto;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.FaturaVista;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto;
import tools.alicerce.financeiro.aplicacao.ListarLancamentos;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.ObterFatura;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.RegistrarTransferencia;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;
import tools.alicerce.financeiro.aplicacao.TransferenciaRegistrada;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.CicloFatura;

/**
 * Testes de aceite, nos casos de uso, contra o banco. O cartão repete o Nubank real:
 * fechamento dia 18, vencimento dia 26, limite de R$ 7.500,00 e abertura de R$ 580,52 com o marco hoje. As
 * datas saem de hoje, porque a parcela atual depende do relógio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class HistoricoProcedenciaTest {

    private static final String RELOGIO_VALOR = "2574.36";
    private static final String VIAGEM_VALOR = "678.88";

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
    @Autowired ListarLancamentos listar;
    @Autowired DSLContext banco;
    @Autowired PostgreSQLContainer postgres;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;
    private CicloFatura atual;
    private String aberturaCodigo;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
        atual = new CartaoCredito(UUID.randomUUID(), 18, 26, null, null, 1).proximoAVencer(hoje);
        conta("corrente", "Itaú", "1000.00", hoje.toString());
        conta("categoria", "Lazer", null, null);
        conta("fonte_renda", "Salário", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, true, null, null));
        aberturaCodigo = definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Nubank", null, null, null, "580.52",
                hoje.toString(), null, new DadosCartao(18, 26, "7500.00", "Itaú"))).valor().lancamentoAbertura();
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
        assertThat(banco.fetchValue("""
                select count(*) from (select m.lancamento_id from movimento m join conta c on c.id = m.conta_id
                where c.dono_id = ? group by 1 having sum(m.valor_centavos) <> 0) x""", dono)).isEqualTo(0L);
    }

    // os critérios do Nubank real

    @Test
    void comprasEmAndamentoDoNubankBatemComOBanco() {
        ResultadoEscrita<LancamentoRegistrado> relogio = relogio(true);
        LancamentoRegistrado viagem = viagem(true).valor();

        assertThat(relogio.valor().valor()).isEqualTo(RELOGIO_VALOR);
        assertThat(relogio.valor().procedencia()).isEqualTo("exogeno");
        CreditoVisto credito = relogio.valor().credito();
        assertThat(credito.parcelas()).isEqualTo(12);
        assertThat(credito.faturas()).extracting(CreditoVisto.ParcelaVista::parcela).containsExactly("9/12", "10/12", "11/12", "12/12");
        assertThat(credito.emAndamento().parcelaAtual()).isEqualTo(8);
        assertThat(credito.emAndamento().parcelasAnteriores()).isEqualTo(7);
        assertThat(credito.emAndamento().valorAnterior()).isEqualTo("1501.71");
        assertThat(credito.emAndamento().valorNaAbertura()).isEqualTo("214.53");
        assertThat(credito.emAndamento().valorEmAberto()).isEqualTo("858.12");
        assertThat(viagem.credito().emAndamento().parcelaAtual()).isEqualTo(2);

        assertThat(saldo("Nubank")).isEqualTo("-1778.08");
        assertThat(disponivel()).isEqualTo("5721.92");

        FaturaVista fatura = fatura(atual.periodo());
        assertThat(fatura.total()).isEqualTo("580.52");
        assertThat(fatura.abertura().valor()).isEqualTo("580.52");
        assertThat(fatura.abertura().detalhado()).isEqualTo("384.25");
        assertThat(fatura.abertura().semDetalhe()).isEqualTo("196.27");
        assertThat(fatura.abertura().parcelas()).extracting(FaturaVista.ParcelaDetalhada::parcela).containsExactlyInAnyOrder("8/12", "2/4");

        // Nenhuma fatura antes da fatura da abertura.
        assertThat(banco.fetchValue("select min(periodo) from fatura where dono_id = ?", dono)).isEqualTo(atual.periodo());
    }

    @Test
    void oGastoApareceCheioComANumeracaoRealESaldoDeAberturaRecebeOQueNaoPassouPeloCartao() {
        LancamentoRegistrado relogio = relogio(true).valor();

        LancamentoVisto visto = resolver.executar(chamador, relogio.id());
        assertThat(visto.valor()).isEqualTo(RELOGIO_VALOR);
        assertThat(visto.parcelas()).isEqualTo(12);
        assertThat(visto.contaHistorica()).isEqualTo("Nubank");
        assertThat(visto.movimentos()).filteredOn(m -> "Saldo de abertura".equals(m.conta())).extracting(LancamentoVisto.MovimentoVisto::parcela)
                .containsExactlyInAnyOrder("1/12", "2/12", "3/12", "4/12", "5/12", "6/12", "7/12", "8/12");
        assertThat(visto.movimentos()).filteredOn(m -> "8/12".equals(m.parcela())).singleElement()
                .satisfies(m -> assertThat(m.faturaAbertura()).isEqualTo(fatura(atual.periodo()).id()));
        assertThat(visto.movimentos()).filteredOn(m -> "Nubank".equals(m.conta())).hasSize(4);
    }

    @Test
    void valorParcelaSubstituiOTotalEParcelaAtualSoConfere() {
        ResultadoEscrita<LancamentoRegistrado> simulada = registrarGasto.executar(chamador, compra(null, "214.53", 12, dataDaParcela(8), 8, true, true));

        assertThat(simulada.simulado()).isTrue();
        assertThat(simulada.valor().valor()).isEqualTo(RELOGIO_VALOR);
        assertThat(saldo("Nubank")).isEqualTo("-580.52");

        ErroTipado errada = erro(() -> registrarGasto.executar(chamador, compra(RELOGIO_VALOR, null, 12, dataDaParcela(8), 7, true, null)));
        assertThat(errada.campo()).isEqualTo("parcela_atual");
        assertThat(errada.mensagem()).contains("8/12");

        ErroTipado incoerente = erro(() -> registrarGasto.executar(chamador, compra("100.00", "214.53", 12, dataDaParcela(8), null, true, null)));
        assertThat(incoerente.campo()).isEqualTo("valor_parcela");
    }

    @Test
    void parcelaNaFaturaComAberturaExigeRespostaENaoPassaDoTeto() {
        ErroTipado semResposta = erro(() -> relogio(null));
        assertThat(semResposta.camposFaltando()).containsExactly("parcela_na_abertura");
        assertThat(semResposta.sugestao()).contains("já está dentro");
        assertThat(saldo("Nubank")).isEqualTo("-580.52");

        ErroTipado acimaDoTeto = erro(() -> registrarGasto.executar(chamador, compra("7000.00", null, 2, dataDaParcela(2), null, true, null)));
        assertThat(acimaDoTeto.campo()).isEqualTo("parcela_na_abertura");
        assertThat(acimaDoTeto.mensagem()).contains("580.52");
        assertThat(fatura(atual.periodo()).abertura().detalhado()).isEqualTo("0.00");
    }

    @Test
    void parcelaForaDaAberturaViraDividaNaFatura() {
        relogio(false);

        assertThat(saldo("Nubank")).isEqualTo("-1653.17");
        assertThat(fatura(atual.periodo()).total()).isEqualTo("795.05");
        assertThat(fatura(atual.periodo()).abertura().detalhado()).isEqualTo("0.00");
    }


    @Test
    void gastoEGanhoAntesDoMarcoNaoMudamOSaldoESaemComoExogenos() {
        LancamentoRegistrado gasto = aVista("50.00", hoje.minusDays(40).toString(), "Pix").valor();
        LancamentoRegistrado ganho = ganho("200.00", hoje.minusDays(40).toString()).valor();

        assertThat(gasto.procedencia()).isEqualTo("exogeno");
        assertThat(gasto.conta()).isEqualTo("Itaú");
        assertThat(gasto.saldoConta()).isEqualTo("1000.00");
        assertThat(gasto.valor()).isEqualTo("50.00");
        assertThat(ganho.procedencia()).isEqualTo("exogeno");
        assertThat(ganho.saldoConta()).isEqualTo("1000.00");
        assertThat(saldo("Itaú")).isEqualTo("1000.00");

        assertThat(lista("exogeno")).extracting(LancamentoVisto::id).contains(gasto.id(), ganho.id());
        assertThat(lista("endogeno")).extracting(LancamentoVisto::id).doesNotContain(gasto.id(), ganho.id());
        // O filtro por conta acha o fato, mesmo sem movimento nela.
        assertThat(listar.executar(chamador, new ListarLancamentos.Entrada(null, null, "Itaú", null, null, null, null, null, null, null, null)).lancamentos())
                .extracting(LancamentoVisto::id).contains(gasto.id(), ganho.id());
    }

    @Test
    void naDataDoMarcoOFatoEntraNaConta() {
        LancamentoRegistrado gasto = aVista("50.00", hoje.toString(), "Pix").valor();

        assertThat(gasto.procedencia()).isEqualTo("endogeno");
        assertThat(saldo("Itaú")).isEqualTo("950.00");
    }

    @Test
    void transferenciaEPagamentoAntesDoMarcoSaoRecusados() {
        conta("poupanca", "Reserva", null, "2020-01-01");

        ErroTipado transferencia = erro(() -> transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "10.00", "Itaú", "Reserva",
                hoje.minusDays(1).toString(), null, null, null, null, null)));
        ErroTipado pagamento = erro(() -> pagar("10.00", hoje.minusDays(1).toString()));

        assertThat(transferencia.campo()).isEqualTo("data");
        assertThat(transferencia.mensagem()).contains("marco de 'Itaú'");
        assertThat(pagamento.campo()).isEqualTo("data");
    }

    @Test
    void dataDeAberturaFuturaERecusadaEContaSemDataTemOMarcoHoje() {
        ErroTipado futura = erro(() -> conta("poupanca", "Futura", null, hoje.plusDays(1).toString()));
        ContaDefinida semData = conta("poupanca", "Nova", null, null).valor();

        assertThat(futura.campo()).isEqualTo("data_abertura");
        assertThat(semData.marco()).isEqualTo(hoje.toString());
        assertThat(obterContexto.executar(chamador).contas()).filteredOn(c -> c.nome().equals("Itaú")).singleElement()
                .satisfies(c -> assertThat(c.marco()).isEqualTo(hoje.toString()));
    }


    @Test
    void corrigirCompraEmAndamentoMantemAbertoENaoDeixaEfeitoNasFaturas() {
        LancamentoRegistrado relogio = relogio(true).valor();

        CorrecaoRegistrada corrigida = corrigir(relogio.id(), new Alteracoes("2400.00", null, null, null, null, null, null, null, null)).valor();

        // Sem nova resposta, a anterior vale: a parcela de 200,00 continua dentro da abertura.
        assertThat(saldo("Nubank")).isEqualTo("-1380.52");
        assertThat(fatura(atual.periodo()).abertura().detalhado()).isEqualTo("200.00");
        assertThat(corrigida.movimentos()).filteredOn(m -> "8/12".equals(m.parcela())).singleElement()
                .satisfies(m -> assertThat(m.valor()).isEqualTo("-200.00"));
        assertThat(fatura(atual.periodo()).total()).isEqualTo("580.52");

        anular(corrigida.novo());
        assertThat(saldo("Nubank")).isEqualTo("-580.52");
        assertThat(disponivel()).isEqualTo("6919.48");
        assertThat(fatura(atual.periodo()).abertura().detalhado()).isEqualTo("0.00");
    }

    @Test
    void desfazerCompraEmAndamentoLimpaAsFaturas() {
        ResultadoEscrita<LancamentoRegistrado> relogio = relogio(true);

        confirmar(relogio.operacao().referencia().id());

        assertThat(saldo("Nubank")).isEqualTo("-580.52");
        assertThat(fatura(atual.periodo()).abertura().detalhado()).isEqualTo("0.00");
        assertThat(fatura(atual.periodo()).total()).isEqualTo("580.52");
    }

    @Test
    void mudarADataPodeAtravessarOMarco() {
        LancamentoRegistrado gasto = aVista("50.00", hoje.toString(), "Pix").valor();

        CorrecaoRegistrada antes = corrigir(gasto.id(), new Alteracoes(null, null, hoje.minusDays(3).toString(), null, null, null, null, null, null)).valor();
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
        assertThat(antes.procedencia()).isEqualTo("exogeno");

        CorrecaoRegistrada depois = corrigir(antes.novo(), new Alteracoes(null, null, hoje.toString(), null, null, null, null, null, null)).valor();
        assertThat(saldo("Itaú")).isEqualTo("950.00");
        assertThat(depois.procedencia()).isEqualTo("endogeno");
    }

    @Test
    void corrigirGanhoExogenoManteConta() {
        LancamentoRegistrado ganho = ganho("200.00", hoje.minusDays(5).toString()).valor();

        CorrecaoRegistrada corrigido = corrigir(ganho.id(), new Alteracoes("250.00", "Bônus", null, null, null, null, null, null, null)).valor();

        LancamentoVisto visto = resolver.executar(chamador, corrigido.novo());
        assertThat(visto.procedencia()).isEqualTo("exogeno");
        assertThat(visto.contaHistorica()).isEqualTo("Itaú");
        assertThat(visto.valor()).isEqualTo("250.00");
        assertThat(saldo("Itaú")).isEqualTo("1000.00");
    }

    @Test
    void lancamentoAntigoComDataAntesDoMarcoNaoMudaDeProcedenciaAoCorrigirADescricao() {
        // Reproduz um gasto gravado antes da V9: endógeno, anterior ao marco, tocando a conta.
        LancamentoRegistrado gasto = aVista("50.00", hoje.toString(), "Pix").valor();
        banco.execute("update conta set marco_inicial = ? where dono_id = ? and nome = 'Itaú'", hoje.plusDays(1), dono);

        corrigir(gasto.id(), new Alteracoes(null, "Outra descrição", null, null, null, null, null, null, null));

        assertThat(saldo("Itaú")).isEqualTo("950.00");
    }

    @Test
    void aberturaNaoMudaDeDataNemFicaAbaixoDoDetalhe() {
        relogio(true);

        ErroTipado data = erro(() -> corrigir(aberturaCodigo, new Alteracoes(null, null, hoje.minusDays(5).toString(), null, null, null, null, null, null)));
        ErroTipado valor = erro(() -> corrigir(aberturaCodigo, new Alteracoes("100.00", null, null, null, null, null, null, null, null)));
        ErroTipado anular = erro(() -> anular(aberturaCodigo));

        assertThat(data.campo()).isEqualTo("alteracoes.data");
        assertThat(valor.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(valor.mensagem()).contains("214.53");
        assertThat(anular.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);

        corrigir(aberturaCodigo, new Alteracoes("600.00", null, null, null, null, null, null, null, null));
        assertThat(fatura(atual.periodo()).total()).isEqualTo("600.00");
    }


    @Test
    void resumoSeparaOExogenoEACaixaNaoOMistura() {
        String mesPassado = hoje.minusMonths(1).toString().substring(0, 7);
        aVista("50.00", hoje.minusMonths(1).withDayOfMonth(10).toString(), "Pix");
        ganho("200.00", hoje.minusMonths(1).withDayOfMonth(11).toString());

        ResumoVisto competencia = resumo(mesPassado, "competencia");
        ResumoVisto caixa = resumo(mesPassado, "caixa");

        assertThat(competencia.saidas()).isEqualTo("50.00");
        assertThat(competencia.entradas()).isEqualTo("200.00");
        assertThat(competencia.historico().saidasExogenas()).isEqualTo("50.00");
        assertThat(competencia.historico().entradasExogenas()).isEqualTo("200.00");
        assertThat(competencia.historico().nota()).contains("nunca some");
        assertThat(competencia.maioresSaidas()).singleElement().satisfies(d -> assertThat(d.procedencia()).isEqualTo("exogeno"));
        assertThat(competencia.avisoHistorico()).contains("Histórico parcial").contains("Itaú desde " + hoje);
        assertThat(caixa.saidas()).isEqualTo("0.00");
        assertThat(caixa.entradas()).isEqualTo("0.00");
        assertThat(caixa.historico()).isNull();
    }


    @Test
    void naCaixaContamGanhoEGastoSemProcedenciaGravadaEACompetenciaNaoMuda() throws SQLException {
        String mes = hoje.toString().substring(0, 7);
        String ganho = ganho("1200.00", hoje.toString()).valor().id();
        String gasto = aVista("50.00", hoje.toString(), "Pix").valor().id();
        ResumoVisto competenciaAntes = resumo(mes, "competencia");

        semProcedencia(ganho, gasto);

        ResumoVisto caixa = resumo(mes, "caixa");
        ResumoVisto competencia = resumo(mes, "competencia");
        assertThat(caixa.entradas()).isEqualTo("1200.00");
        assertThat(caixa.saidas()).isEqualTo("50.00");
        assertThat(competencia.entradas()).isEqualTo(competenciaAntes.entradas()).isEqualTo("1200.00");
        assertThat(competencia.saidas()).isEqualTo(competenciaAntes.saidas());
        assertThat(lista("endogeno")).extracting(LancamentoVisto::id).contains(ganho, gasto);
        assertThat(lista("exogeno")).extracting(LancamentoVisto::id).doesNotContain(ganho, gasto).contains(aberturaCodigo);
    }

    /** Refaz as linhas anteriores ao histórico e à procedência. Só o dono do esquema, sem gatilhos, apaga a procedência gravada. */
    private void semProcedencia(String... codigos) throws SQLException {
        try (Connection dba = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var replica = dba.createStatement();
                var comando = dba.prepareStatement("""
                        update lancamento set procedencia = null where id in
                          (select entidade_id from referencia_curta where tipo_entidade = 'lancamento' and codigo = any (?))""")) {
            replica.execute("set session_replication_role = replica");
            comando.setArray(1, dba.createArrayOf("text", codigos));
            assertThat(comando.executeUpdate()).isEqualTo(codigos.length);
        }
    }

    @Test
    void naCaixaSoContamAsParcelasQueViraramDivida() {
        relogio(true);

        ResumoVisto mesDaAbertura = resumo(atual.periodo(), "caixa");
        ResumoVisto mesSeguinte = resumo(LocalDate.parse(atual.periodo() + "-01").plusMonths(1).toString().substring(0, 7), "caixa");
        ResumoVisto mesDaCompra = resumo(dataDaParcela(8).substring(0, 7), "competencia");

        assertThat(mesDaAbertura.saidas()).isEqualTo("0.00");
        assertThat(mesSeguinte.saidas()).isEqualTo("214.53");
        assertThat(mesDaCompra.saidas()).isEqualTo(RELOGIO_VALOR);
        assertThat(mesDaCompra.historico().saidasExogenas()).isEqualTo(RELOGIO_VALOR);
    }


    @Test
    void corrigirCompraEmFaturaComPagamentoPreservaOPagamento() {
        Pago cenario = faturaComPagamento();

        ResultadoEscrita<CorrecaoRegistrada> simulada = corrigir.executar(chamador, correcao(chave(), cenario.abajur().valor().id(), "110.00", true));
        assertThat(simulada.simulado()).isTrue();
        assertThat(faturaInter(cenario).total()).isEqualTo("725.52");

        String chave = chave();
        ResultadoEscrita<CorrecaoRegistrada> corrigida = corrigir.executar(chamador, correcao(chave, cenario.abajur().valor().id(), "110.00", null));
        ResultadoEscrita<CorrecaoRegistrada> repetida = corrigir.executar(chamador, correcao(chave, cenario.abajur().valor().id(), "110.00", null));

        assertThat(repetida.repetido()).isTrue();
        assertThat(repetida.valor().novo()).isEqualTo(corrigida.valor().novo());
        FaturaVista fatura = faturaInter(cenario);
        assertThat(fatura.total()).isEqualTo("720.52");
        assertThat(fatura.pago()).isEqualTo("665.52");
        assertThat(fatura.restante()).isEqualTo("55.00");
        assertThat(fatura.situacao()).isEqualTo("parcialmente_paga");
        assertThat(fatura.pagamentos()).hasSize(1);
        assertThat(fatura.itens()).filteredOn(i -> i.descricao().equals("Abajur")).singleElement()
                .satisfies(i -> assertThat(i.valorNaFatura()).isEqualTo("55.00"));
    }

    @Test
    void correcaoQueDeixariaPagamentoSobrandoERecusadaComOsNumeros() {
        Pago cenario = faturaComPagamento();

        ErroTipado recusa = erro(() -> corrigir(cenario.base().valor().id(), new Alteracoes("500.00", null, null, null, null, null, null, null, null)));

        assertThat(recusa.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(recusa.mensagem()).contains("725.52").contains("560.00").contains("665.52").contains("105.52");
        assertThat(recusa.candidatos()).hasSize(1);
        assertThat(recusa.sugestao()).contains("O pagamento fica como está");
        assertThat(faturaInter(cenario).total()).isEqualTo("725.52");
    }

    @Test
    void anularEDesfazerSeguemAMesmaRegra() {
        Pago cenario = faturaComPagamento();

        ErroTipado anulacao = erro(() -> anular(cenario.base().valor().id()));
        ErroTipado desfeita = erro(() -> confirmar(cenario.base().operacao().referencia().id()));
        assertThat(anulacao.codigo()).isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        assertThat(desfeita.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);
        assertThat(desfeita.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(cenario.pagamento().operacao().referencia().id());

        // Sem o abajur, a fatura fica com 665,52, exatamente o pago: cabe, mesmo a compra sendo anterior ao pagamento.
        confirmar(cenario.abajur().operacao().referencia().id());
        FaturaVista fatura = faturaInter(cenario);
        assertThat(fatura.total()).isEqualTo("665.52");
        assertThat(fatura.situacao()).isEqualTo("paga");
    }

    @Test
    void previaDoDesfazerDizComoAplicar() {
        ResultadoEscrita<LancamentoRegistrado> gasto = aVista("50.00", hoje.toString(), "Pix");

        ReversaoRegistrada previa = desfazer.executar(chamador, new Desfazer.Entrada(null, gasto.operacao().referencia().id(), null, null)).valor();

        assertThat(previa.proximoPasso()).contains("confirmacao").contains("Não peça outra prévia");
    }

    private record Pago(ResultadoEscrita<LancamentoRegistrado> base, ResultadoEscrita<LancamentoRegistrado> abajur,
            ResultadoEscrita<TransferenciaRegistrada> pagamento, String periodo) {}

    /** O caso do teste pelo ChatGPT: fatura de 725,52, com o abajur de 120,00 em 2x e 665,52 pagos. Cartão sem abertura. */
    private Pago faturaComPagamento() {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Inter", null, null, null, null, hoje.toString(), null,
                new DadosCartao(18, 26, "9000.00", "Itaú")));
        String periodo = new CartaoCredito(UUID.randomUUID(), 18, 26, null, null, 1).cicloDaCompra(hoje).periodo();
        ResultadoEscrita<LancamentoRegistrado> base = noInter("665.52", "Base", 1);
        ResultadoEscrita<LancamentoRegistrado> abajur = noInter("120.00", "Abajur", 2);
        ResultadoEscrita<TransferenciaRegistrada> pagamento = transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "665.52", "Itaú", "Inter",
                hoje.toString(), periodo, null, null, true, null));
        return new Pago(base, abajur, pagamento, periodo);
    }

    private ResultadoEscrita<LancamentoRegistrado> noInter(String valor, String descricao, int parcelas) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, descricao, "Inter", "conversa", hoje.toString(), "Lazer", null, null,
                null, null, null, null, true, null, null, parcelas, null));
    }

    private FaturaVista faturaInter(Pago cenario) {
        return obterFatura.executar(chamador, "Inter", cenario.periodo());
    }

    private ResultadoEscrita<LancamentoRegistrado> relogio(Boolean naAbertura) {
        return registrarGasto.executar(chamador, compra(RELOGIO_VALOR, null, 12, dataDaParcela(8), 8, naAbertura, null));
    }

    private ResultadoEscrita<LancamentoRegistrado> viagem(Boolean naAbertura) {
        return registrarGasto.executar(chamador, compra(VIAGEM_VALOR, null, 4, dataDaParcela(2), 2, naAbertura, null));
    }

    /** A data de uma compra cuja parcela {@code numero} está na fatura da abertura. */
    private String dataDaParcela(int numero) {
        return atual.fechaEm().minusMonths(numero - 1L).toString();
    }

    private RegistrarGasto.Entrada compra(String valor, String valorParcela, int parcelas, String data, Integer parcelaAtual, Boolean naAbertura, Boolean simular) {
        return new RegistrarGasto.Entrada(chave(), null, valor, "Compra em andamento", "Nubank", "conversa", data, "Lazer", null, null, null, null, null, null,
                true, simular, null, parcelas, null, valorParcela, parcelaAtual, naAbertura);
    }

    private ResultadoEscrita<LancamentoRegistrado> aVista(String valor, String data, String meio) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra " + valor, meio, "conversa", data, "Lazer", null, null, null,
                null, null, null, true, null));
    }

    private ResultadoEscrita<LancamentoRegistrado> ganho(String valor, String data) {
        return registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, valor, "Bico", "Itaú", "Salário", null, data, null, null, null, null, null,
                true, null));
    }

    private ResultadoEscrita<ContaDefinida> conta(String tipo, String nome, String saldo, String data) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, data, null));
    }

    private ResultadoEscrita<TransferenciaRegistrada> pagar(String valor, String data) {
        return transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, valor, "Itaú", "Nubank", data, atual.periodo(), null, null, true, null));
    }

    private ResultadoEscrita<CorrecaoRegistrada> corrigir(String lancamento, Alteracoes alteracoes) {
        return corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, "corrigir", alteracoes, "Errei", null));
    }

    private CorrigirLancamento.Entrada correcao(String chave, String lancamento, String valor, Boolean simular) {
        return new CorrigirLancamento.Entrada(chave, null, lancamento, "corrigir", new Alteracoes(valor, null, null, null, null, null, null, null, null), "Errei", simular);
    }

    private void anular(String lancamento) {
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, lancamento, "anular", null, "Errei", null));
    }

    private void confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private FaturaVista fatura(String periodo) {
        return obterFatura.executar(chamador, "Nubank", periodo);
    }

    private String saldo(String nome) {
        return obterContexto.executar(chamador).contas().stream().filter(c -> c.nome().equals(nome)).findFirst().orElseThrow().saldo();
    }

    private String disponivel() {
        return obterContexto.executar(chamador).cartoes().get(0).limiteDisponivel();
    }

    private ResumoVisto resumo(String periodo, String visao) {
        return resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, periodo, null, null, null, visao, null)).resumo();
    }

    private List<LancamentoVisto> lista(String procedencia) {
        return listar.executar(chamador, new ListarLancamentos.Entrada(null, null, null, null, null, null, null, null, null, null, procedencia)).lancamentos();
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
