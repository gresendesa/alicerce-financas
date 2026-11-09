package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
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
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CortesNecessarios;
import tools.alicerce.financeiro.aplicacao.CortesVisto;
import tools.alicerce.financeiro.aplicacao.Declarar;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeta;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.MetaVista;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.ProjecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista.MesVisto;
import tools.alicerce.financeiro.aplicacao.Projetar;
import tools.alicerce.financeiro.aplicacao.RegistrarTransferencia;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.TransferenciaRegistrada;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;

/**
 * Testes de aceite (metas de valor), nos casos de uso, contra o banco. A data alvo fica no fim
 * do 12º mês contando o de hoje, para o aporte necessário não depender do dia em que o teste roda.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class MetasTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeta definirMeta;
    @Autowired RegistrarTransferencia transferir;
    @Autowired CorrigirLancamento corrigir;
    @Autowired Desfazer desfazer;
    @Autowired ObterContexto obterContexto;
    @Autowired ResolverReferencia resolver;
    @Autowired ResumirPeriodo resumir;
    @Autowired Projetar projetar;
    @Autowired CortesNecessarios cortes;
    @Autowired Declarar declarar;
    @Autowired Vistas vistas;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;
    private YearMonth mes;
    private String dataAlvo;

    @BeforeEach
    void dono() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:profile"), "gui");
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
        mes = YearMonth.from(hoje);
        dataAlvo = mes.plusMonths(11).atEndOfMonth().toString();
        conta("corrente", "Itaú", "5000.00");
        conta("poupanca", "CAIXA", "100.00");
        conta("carteira", "Carteira", "50.00");
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    @Test
    void criarDaOAporteNecessarioEOAporteMudaOProgressoSemSerGasto() {
        ResultadoEscrita<MetaVista> criada = meta("Viagem 2027", "6000.00", dataAlvo, "CAIXA");
        MetaVista m = criada.valor();
        assertThat(m.id()).startsWith("m-");
        assertThat(m.progresso()).isEqualTo("0.00");
        assertThat(m.falta()).isEqualTo("6000.00");
        assertThat(m.mesesRestantes()).isEqualTo(12);
        assertThat(m.aporteMensalNecessario()).isEqualTo("500.00");
        assertThat(m.contaReservada()).isEqualTo("CAIXA");

        // O sentido sai da conta reservada: entrar nela é aporte.
        TransferenciaRegistrada aporte = transferencia("300.00", "Itaú", "CAIXA", "viagem 2027", null).valor();
        assertThat(aporte.tipo()).isEqualTo("aporte_meta");
        assertThat(aporte.meta().movimento()).isEqualTo("aporte");
        assertThat(aporte.meta().progresso()).isEqualTo("300.00");
        // (6.000 − 300) ÷ 12 = 475,00.
        assertThat(aporte.meta().aporteMensalNecessario()).isEqualTo("475.00");

        MetaVista noContexto = obterContexto.executar(chamador).metas().get(0);
        assertThat(noContexto.progresso()).isEqualTo("300.00");
        assertThat(noContexto.percentual()).isEqualTo("5.00");
        MetaVista consultada = (MetaVista) resolver.resolver(chamador, m.id());
        assertThat(consultada.aportes()).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(aporte.id());
            assertThat(a.tipo()).isEqualTo("aporte");
            assertThat(a.valor()).isEqualTo("300.00");
        });

        // Aporte não é gasto: o resumo do mês não o conta.
        var resumo = resumir.calcularAgora(chamador, new ResumirPeriodo.Entrada(mes.atDay(1).toString(), mes.atEndOfMonth().toString(), null,
                null, null, null, null, null));
        assertThat(resumo.saidas()).isEqualTo("0.00");
        assertThat(resumo.entradas()).isEqualTo("0.00");

        // Sair da reservada é resgate; resgate maior que o progresso é recusado.
        TransferenciaRegistrada resgate = transferencia("100.00", "CAIXA", "Itaú", "Viagem 2027", null).valor();
        assertThat(resgate.meta().movimento()).isEqualTo("resgate");
        assertThat(resgate.meta().progresso()).isEqualTo("200.00");
        assertThat(erro(() -> transferencia("500.00", "CAIXA", "Itaú", "Viagem 2027", null)).campo()).isEqualTo("valor");
        assertThat(erro(() -> transferencia("10.00", "Itaú", "Carteira", "Viagem 2027", null)).campo()).isEqualTo("ate");
        assertThat(erro(() -> transferencia("10.00", "Itaú", "CAIXA", "Viagem 2027", true)).campo()).isEqualTo("resgate");

        // Aporte não se corrige; anular tira o valor do progresso.
        assertThat(erro(() -> corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, aporte.id(), "corrigir",
                new CorrigirLancamento.Alteracoes("1.00", null, null, null, null, null, null, null, null, null, null, null), "teste", null)))
                .campo()).isEqualTo("acao");
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, resgate.id(), "anular", null, "Engano", null));
        assertThat(((MetaVista) resolver.resolver(chamador, m.id())).progresso()).isEqualTo("300.00");

        // A tela padrão da meta mostra o progresso e o aporte.
        Vista tela = vistas.abrir(chamador, new Vistas.Pedido("/fin/m/" + m.id().substring(2), null, null, null, null));
        assertThat(tela.dados().toString()).contains("Viagem 2027", "300.00", "475.00", aporte.id());
    }

    @Test
    void semContaReservadaOSentidoVemDoResgateESemDataNaoHaAporteNecessario() {
        MetaVista m = meta("Reserva de emergência", "10000.00", null, null).valor();
        assertThat(m.aporteMensalNecessario()).isNull();
        assertThat(m.mesesRestantes()).isNull();
        transferencia("400.00", "Itaú", "CAIXA", "Reserva de emergência", null);
        TransferenciaRegistrada resgate = transferencia("150.00", "CAIXA", "Itaú", "Reserva de emergência", true).valor();
        assertThat(resgate.meta().progresso()).isEqualTo("250.00");

        assertThat(erro(() -> cortes.executar(chamador, new CortesNecessarios.Entrada(null, null, null, null, "Reserva de emergência")))
                .campo()).isEqualTo("meta");
        assertThat(erro(() -> meta("reserva de EMERGENCIA", "1.00", null, null)).codigo()).isEqualTo(CodigoErro.CONFLITO);
        assertThat(erro(() -> meta("Outra", "1.00", hoje.minusDays(1).toString(), null)).campo()).isEqualTo("data_alvo");
        assertThat(erro(() -> meta("Outra", "1.00", null, "Itaú")).campo()).isEqualTo("conta_reservada");
        assertThat(erro(() -> definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, "Outra", null, "1.00", null, 6, null, null,
                null))).campo()).isEqualTo("prioridade");
    }

    @Test
    void desfazerACriacaoComAporteEBloqueadoEDesfazerAAlteracaoRestaura() {
        ResultadoEscrita<MetaVista> criada = meta("Carro", "20000.00", dataAlvo, "CAIXA");
        String id = criada.valor().id();
        ResultadoEscrita<MetaVista> alterada = definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, id, null, null, "24000.00",
                "", 1, "", null, null));
        assertThat(alterada.valor().valorAlvo()).isEqualTo("24000.00");
        assertThat(alterada.valor().dataAlvo()).isNull();
        assertThat(alterada.valor().contaReservada()).isNull();

        confirmar(alterada.operacao().referencia().id());
        MetaVista restaurada = (MetaVista) resolver.resolver(chamador, id);
        assertThat(restaurada.valorAlvo()).isEqualTo("20000.00");
        assertThat(restaurada.contaReservada()).isEqualTo("CAIXA");

        TransferenciaRegistrada aporte = transferencia("100.00", "Itaú", "CAIXA", id, null).valor();
        ErroTipado bloqueado = erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, criada.operacao().referencia().id(), null, null)));
        assertThat(bloqueado.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);

        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, aporte.id(), "anular", null, "Engano", null));
        confirmar(criada.operacao().referencia().id());
        assertThat(obterContexto.executar(chamador).metas()).isEmpty();
        assertThat(erro(() -> resolver.resolver(chamador, id)).codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
        // Arquivada, o nome fica livre.
        assertThat(meta("Carro", "1000.00", null, null).valor().id()).isNotEqualTo(id);
    }

    @Test
    void projetarComAporteMetaDaADataProjetadaEAContaReservadaFicaForaDoSaldo() {
        MetaVista m = meta("Viagem 2027", "6000.00", dataAlvo, "CAIXA").valor();
        transferencia("300.00", "Itaú", "CAIXA", m.id(), null);

        ProjecaoRegistrada registrada = projetar.executar(chamador, new Projetar.Entrada(12, List.of(new Projetar.Cenario("Guardar 500",
                List.of(aporte(m.id(), "500.00", null, null))), new Projetar.Cenario("Guardar 300 por três meses",
                List.of(aporte("Viagem 2027", "300.00", null, mes.plusMonths(2).toString()))))));
        ProjecaoVista p = registrada.projecao();

        // CAIXA está reservada: só Itaú (4.700,00) e a carteira (50,00) contam.
        assertThat(p.saldoPartida()).isEqualTo("4750.00");
        assertThat(p.contasPartida()).extracting(CortesVisto.ContaPartida::nome).containsExactlyInAnyOrder("Itaú", "Carteira");
        assertThat(p.ressalvas()).extracting(CortesVisto.Ressalva::codigo).contains("conta_reservada_meta");

        // A linha de base não tem aporte planejado: a meta não chega lá.
        assertThat(p.linhaBase().meses()).extracting(MesVisto::aportes).containsOnly("0.00");
        assertThat(p.linhaBase().metas()).singleElement().satisfies(x -> {
            assertThat(x.alcancaNoHorizonte()).isFalse();
            assertThat(x.situacao()).isEqualTo("Não alcança no horizonte");
        });

        // 300 + 11 × 500 = 5.800; o 12º aporte é só o que falta, 200,00, e a meta chega no último mês, no prazo.
        ProjecaoVista.CenarioVisto guardar = p.cenarios().get(0);
        assertThat(guardar.meses().get(0).aportes()).isEqualTo("500.00");
        assertThat(guardar.meses().get(11).aportes()).isEqualTo("200.00");
        assertThat(guardar.meses().get(0).saldoFinalP50()).isEqualTo("4250.00");
        assertThat(guardar.diferencaLinhaBase().saldoFinalP50()).isEqualTo("-5700.00");
        assertThat(guardar.metas()).singleElement().satisfies(x -> {
            assertThat(x.mesProjetado()).isEqualTo(mes.plusMonths(11).toString());
            assertThat(x.noPrazo()).isTrue();
            assertThat(x.aportesPlanejados()).isEqualTo("5700.00");
            assertThat(x.situacao()).isEqualTo("No prazo");
        });
        assertThat(guardar.hipoteses().get(0).itens()).hasSize(12);

        ProjecaoVista.CenarioVisto pouco = p.cenarios().get(1);
        assertThat(pouco.meses()).extracting(MesVisto::aportes).containsExactly("300.00", "300.00", "300.00", "0.00", "0.00", "0.00", "0.00",
                "0.00", "0.00", "0.00", "0.00", "0.00");
        assertThat(pouco.metas().get(0).alcancaNoHorizonte()).isFalse();

        // A tela do retrato mostra a coluna de aportes e a tabela das metas.
        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrada.urlRetrato().substring(registrada.urlRetrato().indexOf("/fin/")),
                null, null, null, null));
        assertThat(tela.componentes().toString()).contains("Aportes em metas", "Alcança em");
        assertThat(tela.dados().toString()).contains("No prazo", "Não alcança no horizonte");

        assertThat(erro(() -> projetar.executar(chamador, new Projetar.Entrada(3, List.of(new Projetar.Cenario("x",
                List.of(aporte(m.id(), "500.00", null, mes.plusMonths(5).toString()))))))).campo()).isEqualTo("cenarios[0].hipoteses[0].ate");
        assertThat(erro(() -> projetar.executar(chamador, new Projetar.Entrada(3, List.of(new Projetar.Cenario("x",
                List.of(aporte(m.id(), null, null, null))))))).camposFaltando()).containsExactly("cenarios[0].hipoteses[0].valor");
    }

    @Test
    void cortesComAlvoMetaSomamOPisoEOAporteDoMes() {
        MetaVista m = meta("Viagem 2027", "6000.00", dataAlvo, "CAIXA").valor();

        CortesVisto semPiso = cortes.executar(chamador, new CortesNecessarios.Entrada(null, null, null, null, "Viagem 2027")).cortes();
        assertThat(semPiso.alvo().tipo()).isEqualTo("meta");
        assertThat(semPiso.alvo().meta().aporteMes()).isEqualTo("500.00");
        assertThat(semPiso.alvo().meta().piso()).isEqualTo("0.00");
        assertThat(semPiso.alvo().valor()).isEqualTo("500.00");
        assertThat(semPiso.saldoPartida()).isEqualTo("5050.00");

        declarar.executar(chamador, new Declarar.Entrada(chave(), null, "piso_periodo", "1000.00", null, null));
        var registrado = cortes.executar(chamador, new CortesNecessarios.Entrada("meta", null, null, null, m.id()));
        CortesVisto comPiso = registrado.cortes();
        assertThat(comPiso.alvo().valor()).isEqualTo("1500.00");
        assertThat(comPiso.alvo().operacaoDeclaracao()).startsWith("o-");
        assertThat(comPiso.distancia()).isEqualTo("3550.00");

        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrado.urlRetrato().substring(registrado.urlRetrato().indexOf("/fin/")),
                null, null, null, null));
        assertThat(tela.dados().toString()).contains("Fechar o mês no piso e guardar para a meta", "Viagem 2027");
        assertThat(erro(() -> cortes.executar(chamador, new CortesNecessarios.Entrada("saldo_final", "1.00", null, null, "Viagem 2027"))).campo())
                .isEqualTo("meta");
    }

    private ResultadoEscrita<MetaVista> meta(String nome, String alvo, String data, String reservada) {
        return definirMeta.executar(chamador, new DefinirMeta.Entrada(chave(), null, null, nome, null, alvo, data, null, reservada, null, null));
    }

    private ResultadoEscrita<TransferenciaRegistrada> transferencia(String valor, String de, String ate, String meta, Boolean resgate) {
        return transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, valor, de, ate, null, null, null, null, true, null, null,
                meta, resgate));
    }

    private static Projetar.Hipotese aporte(String meta, String valor, String de, String ate) {
        return new Projetar.Hipotese("aporte_meta", valor, null, null, null, null, null, null, null, null, null, null, de, meta, ate);
    }

    private void conta(String tipo, String nome, String saldo) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, hoje.toString(), null));
    }

    private void confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
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
