package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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
import tools.alicerce.financeiro.aplicacao.Agenda;
import tools.alicerce.financeiro.aplicacao.CompromissoVisto;
import tools.alicerce.financeiro.aplicacao.ConferenciaRegistrada;
import tools.alicerce.financeiro.aplicacao.ConferirSaldo;
import tools.alicerce.financeiro.aplicacao.Contexto;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.ItemAgenda;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.ListarCompromissos;
import tools.alicerce.financeiro.aplicacao.ObterAgenda;
import tools.alicerce.financeiro.aplicacao.OcorrenciaVista;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.ObterFatura;
import tools.alicerce.financeiro.aplicacao.OcorrenciaResolvida;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverOcorrencia;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;

/** Testes de aceite da conferência e dos compromissos, nos casos de uso, contra o banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class ConferenciaCompromissosTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired CorrigirLancamento corrigir;
    @Autowired Desfazer desfazer;
    @Autowired ObterContexto obterContexto;
    @Autowired ObterFatura obterFatura;
    @Autowired ResumirPeriodo resumir;
    @Autowired ConferirSaldo conferir;
    @Autowired DefinirCompromisso definirCompromisso;
    @Autowired ListarCompromissos listar;
    @Autowired ResolverOcorrencia resolverOcorrencia;
    @Autowired ObterAgenda obterAgenda;
    @Autowired ResolverReferencia resolver;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:profile"), "gui");
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
        conta("corrente", "Itaú", "1000.00", hoje.minusDays(60).toString());
        conta("categoria", "Moradia", null, null);
        conta("categoria", "Tributos", null, null);
        conta("fonte_renda", "Reembolsos", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, true, null, null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Nubank", null, null, null, null, null, null,
                new DadosCartao(10, 17, "5000.00", "Itaú")));
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
        assertThat(banco.fetchValue("""
                select count(*) from (select m.lancamento_id from movimento m join conta c on c.id = m.conta_id
                where c.dono_id = ? group by 1 having sum(m.valor_centavos) <> 0) x""", dono)).isEqualTo(0L);
    }

    // ---- conferência de conta ----

    @Test
    void semDiferencaAContaFicaConferidaESaiDaListaDeVencidas() {
        // Nunca conferida, com marco há 60 dias: vencida desde 30 dias depois do marco.
        assertThat(contaNoContexto("Itaú").conferenciaVencida()).isTrue();
        assertThat(resumoDoMes().conferenciasVencidas()).extracting(c -> c.nome()).contains("Itaú");
        assertThat(obterAgenda.executar(chamador, new ObterAgenda.Entrada(null, null, null)).conferenciasVencidas())
                .extracting(c -> c.nome()).contains("Itaú");

        ConferenciaRegistrada c = conferirConta("Itaú", "1000.00", null).valor();

        assertThat(c.situacao()).isEqualTo("conferida");
        assertThat(c.diferenca()).isEqualTo("0.00");
        assertThat(c.saldoCalculado()).isEqualTo("1000.00");
        assertThat(c.valeAte()).isEqualTo(hoje.plusDays(30).toString());
        Contexto.ContaContexto itau = contaNoContexto("Itaú");
        assertThat(itau.ultimaConferencia()).isEqualTo(hoje.toString());
        assertThat(itau.situacaoConferencia()).isEqualTo("conferida");
        assertThat(itau.conferenciaVencida()).isFalse();
        assertThat(resumoDoMes().conferenciasVencidas()).extracting(v -> v.nome()).doesNotContain("Itaú");
    }

    @Test
    void diferencaFicaPendenteComAsOcorrenciasEsperadasComoCandidatas() {
        compromisso("Aluguel", "saida", "recorrente", "150.00", null, null, hoje.minusDays(10).toString(), "Itaú", null);
        long lancamentos = lancamentos();

        ConferenciaRegistrada c = conferirConta("Itaú", "850.00", null).valor();

        assertThat(c.situacao()).isEqualTo("pendente");
        assertThat(c.diferenca()).isEqualTo("-150.00");
        assertThat(c.ocorrenciasCandidatas()).extracting(ItemAgenda::nome).containsExactly("Aluguel");
        assertThat(c.ajuste()).isNull();
        assertThat(c.proximoPasso()).contains("ajustar");
        assertThat(lancamentos()).isEqualTo(lancamentos);
        assertThat(contaNoContexto("Itaú").situacaoConferencia()).isEqualTo("pendente");
        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("1000.00");
    }

    @Test
    void ajusteViraLancamentoContraAjusteNaoIdentificadoEDesfazerEstornaEApagaAConferencia() {
        ResultadoEscrita<ConferenciaRegistrada> r = conferirConta("Itaú", "1012.34", "ajustar");

        assertThat(r.valor().situacao()).isEqualTo("ajustada");
        assertThat(r.valor().ajuste().valor()).isEqualTo("12.34");
        assertThat(r.valor().ajuste().contaTecnica()).isEqualTo("Ajuste não identificado");
        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("1012.34");
        ResumoVisto resumo = resumoDoMes();
        assertThat(resumo.ajustes().valor()).isEqualTo("12.34");
        assertThat(resumo.ajustes().lancamentos()).extracting(ResumoVisto.DestaqueVisto::id).containsExactly(r.valor().ajuste().id());
        assertThat(resumo.entradas()).isEqualTo("0.00");
        assertThat(resumo.saidas()).isEqualTo("0.00");

        confirmar(r.operacao().referencia().id());

        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("1000.00");
        assertThat(contaNoContexto("Itaú").ultimaConferencia()).isNull();
        assertThat(resumoDoMes().ajustes()).isNull();
    }

    @Test
    void anularOAjustePeloLancamentoDeixaAConferenciaPendente() {
        ConferenciaRegistrada c = conferirConta("Itaú", "990.00", "ajustar").valor();

        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, c.ajuste().id(), "anular", null, "Achei o gasto", null));

        assertThat(contaNoContexto("Itaú").situacaoConferencia()).isEqualTo("pendente");
        assertThat(contaNoContexto("Itaú").saldo()).isEqualTo("1000.00");
    }

    @Test
    void dataFuturaEAnteriorAoMarcoSaoRecusadas() {
        assertThat(erro(() -> conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Itaú", null, "1.00",
                hoje.plusDays(1).toString(), null, null))).campo()).isEqualTo("data");
        assertThat(erro(() -> conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Itaú", null, "1.00",
                hoje.minusDays(61).toString(), null, null))).mensagem()).contains("marco");
        assertThat(erro(() -> conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, null, null, null, null, null, null)))
                .camposFaltando()).containsExactly("saldo", "conta");
    }

    @Test
    void contaEmOutraMoedaConfereNaMoedaDelaEAjustaNaContaTecnicaDaMoeda() {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "corrente", "Wise", null, null, null, "100.00",
                hoje.minusDays(5).toString(), null, null, "USD"));

        ConferenciaRegistrada c = conferirConta("Wise", "90.00", "ajustar").valor();

        assertThat(c.moeda()).isEqualTo("USD");
        assertThat(c.diferenca()).isEqualTo("-10.00");
        assertThat(c.ajuste().contaTecnica()).isEqualTo("Ajuste não identificado USD");
        assertThat(contaNoContexto("Wise").saldo()).isEqualTo("90.00");
        // O total do resumo é na moeda do dono: o ajuste em dólar fica na lista, fora da soma.
        assertThat(resumoDoMes().ajustes().valor()).isEqualTo("0.00");
    }

    // ---- conferência de fatura ----

    @Test
    void faturaSoApontaADiferencaComAsComprasCandidatasESemAjuste() {
        noCartao("100.00", "Mercado do mês", 1);
        noCartao("35.50", "Farmácia", 1);
        String periodo = obterFatura.executar(chamador, "Nubank", null).periodo();

        ConferenciaRegistrada c = conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Nubank", periodo, "171.00", null, null,
                null)).valor();

        assertThat(c.situacao()).isEqualTo("pendente");
        assertThat(c.saldoCalculado()).isEqualTo("135.50");
        assertThat(c.diferenca()).isEqualTo("35.50");
        assertThat(c.fatura()).startsWith("f-");
        assertThat(c.comprasCandidatas().get(0).descricao()).isEqualTo("Farmácia");
        assertThat(c.comprasCandidatas().get(0).igualADiferenca()).isTrue();
        assertThat(c.comprasCandidatas()).hasSize(2);
        // Pela fatura, sem conta: o cartão sai dela.
        assertThat(conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, null, c.fatura(), "135.50", null, null, null))
                .valor().situacao()).isEqualTo("conferida");
        assertThat(contaNoContexto("Nubank").ultimaConferencia()).isEqualTo(hoje.toString());

        ErroTipado semAjuste = erro(() -> conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Nubank", periodo, "171.00",
                null, "ajustar", null)));
        assertThat(semAjuste.campo()).isEqualTo("resolucao");
        ErroTipado semFatura = erro(() -> conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Nubank", null, "171.00",
                null, null, null)));
        assertThat(semFatura.camposFaltando()).containsExactly("fatura");
    }

    // ---- compromissos ----

    @Test
    void parceladoMostraReferenciaParcelasPagasERestanteSemGerarLancamento() {
        long lancamentos = lancamentos();

        CompromissoVisto criado = definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, "saida", "parcelado",
                "Parcelamento IPTU", List.of("iptu"), "150.00", null, 6, 2, null, hoje.plusDays(5).minusMonths(2).toString(), null, "Prefeitura",
                "PARC-2026-0042", "Tributos", "Itaú", null, null, null, null)).valor();

        assertThat(lancamentos()).isEqualTo(lancamentos);
        assertThat(criado.id()).startsWith("k-");
        assertThat(criado.situacao()).isEqualTo("ativo");
        assertThat(criado.saldo().pagas()).isEqualTo(2);
        assertThat(criado.saldo().emAberto()).isEqualTo(4);
        assertThat(criado.saldo().valorRestante()).isEqualTo("600.00");
        assertThat(criado.proximas()).extracting(o -> o.parcela()).containsExactly("3/6", "4/6", "5/6");

        CompromissoVisto listado = listar.executar(chamador, new ListarCompromissos.Entrada("iptu", null, null, null, null)).compromissos().get(0);
        assertThat(listado.referencia()).isEqualTo("PARC-2026-0042");
        assertThat(listado.saldo().valorRestante()).isEqualTo("600.00");
        assertThat(listar.executar(chamador, new ListarCompromissos.Entrada(null, "prefeit", null, null, null)).compromissos()).hasSize(1);
        assertThat(obterContexto.executar(chamador).compromissos()).extracting(Contexto.CompromissoContexto::referencia)
                .containsExactly("PARC-2026-0042");
        CompromissoVisto resolvido = (CompromissoVisto) resolver.resolver(chamador, criado.id());
        assertThat(resolvido.ocorrencias()).hasSize(6);
        assertThat(resolvido.ocorrencias().get(0).situacao()).isEqualTo("paga_antes");
    }

    @Test
    void pagamentoBaixaAParcelaCertaPeloValorRealEDesfazerReabre() {
        String id = compromisso("Dívida com a Ana", "saida", "parcelado", "150.00", 6, null, hoje.minusDays(40).toString(), "Itaú", "Ana").id();

        ErroTipado ambiguo = erro(() -> pagar("150.00", id, null));
        assertThat(ambiguo.codigo()).isEqualTo(CodigoErro.REFERENCIA_AMBIGUA);
        assertThat(ambiguo.candidatos()).extracting(ErroTipado.Candidato::id).startsWith("1", "2");

        ResultadoEscrita<LancamentoRegistrado> pago = pagar("155.00", id, "1");
        assertThat(pago.valor().ocorrencia().numero()).isEqualTo(1);
        assertThat(pago.valor().ocorrencia().parcela()).isEqualTo("1/6");
        assertThat(pago.valor().ocorrencia().diferenca()).isEqualTo("5.00");
        assertThat(pago.valor().ocorrencia().valorRestante()).isEqualTo("750.00");
        assertThat(pago.valor().ocorrencia().emAberto()).isEqualTo(5);

        ErroTipado repetido = erro(() -> pagar("150.00", id, "1"));
        assertThat(repetido.codigo()).isEqualTo(CodigoErro.CONFLITO);
        assertThat(repetido.candidatos()).extracting(ErroTipado.Candidato::id).containsExactly(pago.valor().id());

        confirmar(pago.operacao().referencia().id());

        CompromissoVisto depois = um(id);
        assertThat(depois.saldo().pagas()).isZero();
        assertThat(depois.saldo().valorRestante()).isEqualTo("900.00");
    }

    @Test
    void doisPagamentosQuitamAParcelaEDesfazerUmReabreSoAParteDele() {
        String id = compromisso("Acordo com a Bia", "saida", "parcelado", "150.00", 3, null, hoje.plusDays(5).toString(), "Itaú", "Bia").id();

        ResultadoEscrita<LancamentoRegistrado> primeiro = pagar("100.00", id, "1");
        assertThat(primeiro.valor().ocorrencia().diferenca()).isNull();
        assertThat(primeiro.valor().ocorrencia().situacao()).isEqualTo("prevista");
        assertThat(primeiro.valor().ocorrencia().valorPago()).isEqualTo("100.00");
        assertThat(primeiro.valor().ocorrencia().faltaNaOcorrencia()).isEqualTo("50.00");
        CompromissoVisto entre = um(id);
        assertThat(entre.saldo().emAberto()).isEqualTo(3);
        assertThat(entre.saldo().valorPago()).isEqualTo("100.00");
        assertThat(entre.saldo().valorRestante()).isEqualTo("350.00");
        assertThat(entre.proxima().numero()).isEqualTo(1);
        assertThat(entre.proxima().faltaNaOcorrencia()).isEqualTo("50.00");
        assertThat(entre.saldo().pagasEmParte()).isEqualTo(1);
        assertThat(entre.ocorrencias()).extracting(OcorrenciaVista::faltaNaOcorrencia).containsExactly("50.00", "150.00", "150.00");
        assertThat(agenda(hoje.plusDays(10)).vencimentos()).filteredOn(i -> i.nome().equals("Acordo com a Bia"))
                .extracting(ItemAgenda::valor, ItemAgenda::valorPrevisto, ItemAgenda::valorPago).containsExactly(tuple("50.00", "150.00", "100.00"));

        ResultadoEscrita<LancamentoRegistrado> segundo = pagar("50.00", id, null);
        assertThat(segundo.valor().ocorrencia().numero()).as("sem ocorrência, a paga em parte é a única em aberto perto").isEqualTo(1);
        assertThat(segundo.valor().ocorrencia().diferenca()).isEqualTo("0.00");
        assertThat(segundo.valor().ocorrencia().situacao()).isEqualTo("paga");
        assertThat(segundo.valor().ocorrencia().valorPago()).isEqualTo("150.00");
        assertThat(segundo.valor().ocorrencia().faltaNaOcorrencia()).isNull();
        CompromissoVisto quitada = um(id);
        assertThat(quitada.saldo().pagas()).isEqualTo(1);
        assertThat(quitada.saldo().valorRestante()).isEqualTo("300.00");
        assertThat(quitada.saldo().pagasEmParte()).isZero();
        OcorrenciaVista paga = quitada.ocorrencias().get(0);
        assertThat(paga.faltaNaOcorrencia()).isNull();
        assertThat(paga.valorPago()).isEqualTo("150.00");
        assertThat(paga.lancamento()).isEqualTo(segundo.valor().id());
        assertThat(paga.pagamentos()).extracting(OcorrenciaVista.PagamentoVisto::lancamento, OcorrenciaVista.PagamentoVisto::valor)
                .containsExactly(tuple(primeiro.valor().id(), "100.00"), tuple(segundo.valor().id(), "50.00"));
        assertThat(paga.pagamentos()).allSatisfy(p -> assertThat(p.url()).contains("/fin/"));
        assertThat(erro(() -> pagar("10.00", id, "1")).codigo()).isEqualTo(CodigoErro.CONFLITO);

        confirmar(primeiro.operacao().referencia().id());

        CompromissoVisto reaberta = um(id);
        assertThat(reaberta.saldo().pagas()).isZero();
        assertThat(reaberta.saldo().valorPago()).isEqualTo("50.00");
        assertThat(reaberta.saldo().valorRestante()).isEqualTo("400.00");
    }

    @Test
    void resolverOcorrenciaComAParcelaPagaEmParteVinculaCorrigePulaEAdia() {
        String id = compromisso("Acordo com o Caio", "saida", "parcelado", "150.00", 2, null, hoje.minusDays(3).toString(), "Itaú", "Caio").id();
        LancamentoRegistrado cem = avulso("100.00");
        LancamentoRegistrado trinta = avulso("30.00");

        OcorrenciaResolvida primeiro = resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1", "vincular",
                null, cem.id(), null)).valor();
        assertThat(primeiro.ocorrencia().situacao()).isEqualTo("atrasada");
        assertThat(primeiro.ocorrencia().valorPago()).isEqualTo("100.00");
        assertThat(primeiro.saldo().valorAtrasado()).isEqualTo("50.00");
        OcorrenciaResolvida segundo = resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1", "vincular",
                null, trinta.id(), null)).valor();
        assertThat(segundo.ocorrencia().valorPago()).isEqualTo("130.00");
        assertThat(segundo.saldo().valorAtrasado()).isEqualTo("20.00");

        // Corrigir um dos pagamentos leva só o vínculo dele; anular a correção reabre só a parte dele.
        String cinquenta = corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, trinta.id(), "corrigir",
                new Alteracoes("50.00", null, null, null, null, null, null, null, null), "Valor errado", null)).valor().novo();
        assertThat(um(id).saldo().pagas()).isEqualTo(1);
        assertThat(um(id).saldo().valorPago()).isEqualTo("150.00");
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, cinquenta, "anular", null, "Não paguei", null));
        assertThat(um(id).saldo().pagas()).isZero();
        assertThat(um(id).saldo().valorAtrasado()).isEqualTo("50.00");

        ResultadoEscrita<OcorrenciaResolvida> pulada = resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1",
                "pular", null, null, "Caio perdoou o resto"));
        assertThat(pulada.valor().ocorrencia().situacao()).isEqualTo("pulada");
        assertThat(pulada.valor().saldo().valorPago()).isEqualTo("100.00");
        assertThat(pulada.valor().saldo().valorAtrasado()).isEqualTo("0.00");
        assertThat(pulada.valor().saldo().valorRestante()).isEqualTo("150.00");

        confirmar(pulada.operacao().referencia().id());
        assertThat(um(id).saldo().valorAtrasado()).isEqualTo("50.00");

        resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1", "adiar", hoje.plusDays(5).toString(), null, null));
        assertThat(agenda(hoje.plusDays(10)).vencimentos()).filteredOn(i -> i.nome().equals("Acordo com o Caio") && i.parcela().equals("1/2"))
                .extracting(ItemAgenda::valor, ItemAgenda::vencimento).containsExactly(tuple("50.00", hoje.plusDays(5).toString()));
        assertThat(um(id).saldo().atrasadas()).isZero();
    }

    private LancamentoRegistrado avulso(String valor) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Pix ao Caio", "Pix", "conversa", null, "Moradia",
                null, null, null, null, null, null, true, null)).valor();
    }

    @Test
    void correcaoLevaOVinculoEAnularReabre() {
        String id = compromisso("Condomínio", "saida", "unico", "300.00", null, null, hoje.plusDays(3).toString(), "Itaú", null).id();
        LancamentoRegistrado pago = pagar("300.00", id, null).valor();

        String novo = corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, pago.id(), "corrigir",
                new Alteracoes("310.00", null, null, null, null, null, null, null, null), "Valor errado", null)).valor().novo();

        CompromissoVisto corrigido = um(id);
        assertThat(corrigido.saldo().pagas()).isEqualTo(1);
        assertThat(corrigido.saldo().valorPago()).isEqualTo("310.00");
        assertThat(corrigido.situacao()).isEqualTo("quitado");

        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, novo, "anular", null, "Não paguei", null));
        assertThat(um(id).saldo().emAberto()).isEqualTo(1);
    }

    @Test
    void pularAdiarEVincularSemLancamentoNovo() {
        String id = compromisso("Academia", "saida", "recorrente", "99.90", null, null, hoje.minusDays(5).toString(), "Itaú", null).id();
        long lancamentos = lancamentos();

        ResultadoEscrita<OcorrenciaResolvida> pulada = resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, "Academia",
                null, "pular", null, null, "Não cobrou"));
        assertThat(pulada.valor().ocorrencia().situacao()).isEqualTo("pulada");
        assertThat(lancamentos()).isEqualTo(lancamentos);
        assertThat(agenda(hoje.plusDays(10)).atrasados()).extracting(ItemAgenda::nome).doesNotContain("Academia");

        confirmar(pulada.operacao().referencia().id());
        assertThat(agenda(hoje.plusDays(10)).atrasados()).extracting(ItemAgenda::nome).contains("Academia");

        resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1", "adiar", hoje.plusDays(3).toString(), null, null));
        ItemAgenda adiada = agenda(hoje.plusDays(10)).vencimentos().stream().filter(i -> i.nome().equals("Academia")).findFirst().orElseThrow();
        assertThat(adiada.vencimento()).isEqualTo(hoje.plusDays(3).toString());
        assertThat(adiada.vencimentoOriginal()).isEqualTo(hoje.minusDays(5).toString());

        LancamentoRegistrado pix = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "99.90", "Academia", "Pix", "conversa",
                null, "Moradia", null, null, null, null, null, null, true, null)).valor();
        OcorrenciaResolvida vinculada = resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1", "vincular",
                null, pix.id(), null)).valor();
        assertThat(vinculada.ocorrencia().situacao()).isEqualTo("paga");
        assertThat(vinculada.ocorrencia().lancamento()).isEqualTo(pix.id());

        ErroTipado deNovo = erro(() -> resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "2", "vincular",
                null, pix.id(), null)));
        assertThat(deNovo.codigo()).isEqualTo(CodigoErro.CONFLITO);
        ErroTipado pularPaga = erro(() -> resolverOcorrencia.executar(chamador, new ResolverOcorrencia.Entrada(chave(), null, id, "1", "pular",
                null, null, null)));
        assertThat(pularPaga.codigo()).isEqualTo(CodigoErro.CONFLITO);
    }

    @Test
    void agendaTrazAtrasadosVencimentosEFaturasSemSomaDupla() {
        compromisso("Aluguel", "saida", "recorrente", "1500.00", null, null, hoje.minusDays(3).toString(), "Itaú", null);
        compromisso("Reembolso da Ana", "entrada", "unico", "200.00", null, null, hoje.plusDays(10).toString(), "Itaú", "Ana");
        noCartao("300.00", "Geladeira", 3);

        Agenda agenda = agenda(hoje.plusDays(100));

        assertThat(agenda.atrasados()).extracting(ItemAgenda::nome).containsExactly("Aluguel");
        assertThat(agenda.totais().aPagarAtrasado()).isEqualTo("1500.00");
        assertThat(agenda.totais().aReceberNoPeriodo()).isEqualTo("200.00");
        List<ItemAgenda> faturas = agenda.vencimentos().stream().filter(i -> i.tipo().equals("fatura")).toList();
        assertThat(faturas).extracting(ItemAgenda::valor).containsOnly("100.00");
        assertThat(faturas).hasSizeBetween(2, 3);
        assertThat(agenda.vencimentos()).extracting(ItemAgenda::nome).noneMatch(n -> n.contains("Geladeira"));
        // Os totais saem do servidor e batem com os itens.
        BigDecimal aPagar = Stream.concat(agenda.atrasados().stream(), agenda.vencimentos().stream()).filter(i -> i.direcao().equals("saida"))
                .map(i -> new BigDecimal(i.valor())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(new BigDecimal(agenda.totais().aPagar())).isEqualByComparingTo(aPagar);
        assertThat(new BigDecimal(agenda.totais().saldoPrevisto())).isEqualByComparingTo(new BigDecimal("200.00").subtract(aPagar));

        ErroTipado parceladoNoCredito = erro(() -> definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, "saida",
                "parcelado", "Geladeira", null, "100.00", null, 3, null, null, hoje.toString(), null, null, null, null, null, "Nubank", null, null,
                null)));
        assertThat(parceladoNoCredito.campo()).isEqualTo("meio_pagamento");
        ErroTipado compraParcelada = erro(() -> registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "300.00", "Fogão",
                "Nubank", "conversa", null, "Moradia", null, null, null, null, null, null, true, null, null, 3, null, null, null, null, "Aluguel", null)));
        assertThat(compraParcelada.campo()).isEqualTo("compromisso");
    }

    @Test
    void ganhoQuitaCompromissoDeEntrada() {
        String id = compromisso("A Ana me deve", "entrada", "unico", "200.00", null, null, hoje.plusDays(2).toString(), "Itaú", "Ana").id();

        LancamentoRegistrado ganho = registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, "200.00", "Pix da Ana", "Itaú",
                "Reembolsos", null, null, null, null, null, null, null, true, null, null, id, null)).valor();

        assertThat(ganho.ocorrencia().valorRestante()).isEqualTo("0.00");
        assertThat(um(id).situacao()).isEqualTo("quitado");
        ErroTipado lado = erro(() -> pagar("10.00", id, null));
        assertThat(lado.campo()).isEqualTo("compromisso");
    }

    @Test
    void desfazerAlteracaoVoltaEDesfazerCriacaoTiraDaAgendaSoSemPagamento() {
        ResultadoEscrita<CompromissoVisto> criado = definirCompromisso.executar(chamador, entrada(null, "Internet", "saida", "recorrente", "100.00",
                null, null, hoje.plusDays(4).toString(), "Itaú", null));
        String id = criado.valor().id();
        ResultadoEscrita<CompromissoVisto> alterado = definirCompromisso.executar(chamador, entrada(id, null, null, null, "120.00", null, null,
                null, null, null));
        assertThat(alterado.valor().valor()).isEqualTo("120.00");

        confirmar(alterado.operacao().referencia().id());
        assertThat(um(id).valor()).isEqualTo("100.00");

        LancamentoRegistrado pago = pagar("100.00", id, null).valor();
        ErroTipado bloqueado = erro(() -> desfazer.executar(chamador, new Desfazer.Entrada(null, criado.operacao().referencia().id(), null, null)));
        assertThat(bloqueado.codigo()).isEqualTo(CodigoErro.DESFAZER_BLOQUEADO);

        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, pago.id(), "anular", null, "Engano", null));
        confirmar(criado.operacao().referencia().id());
        assertThat(listar.executar(chamador, new ListarCompromissos.Entrada(null, null, null, null, null)).compromissos()).isEmpty();
        assertThat(agenda(hoje.plusDays(60)).vencimentos()).extracting(ItemAgenda::nome).doesNotContain("Internet");
    }

    @Test
    void encerrarTiraOFuturoEMantemOAtrasado() {
        String id = compromisso("Streaming", "saida", "recorrente", "39.90", null, null, hoje.minusDays(2).toString(), "Itaú", null).id();

        CompromissoVisto encerrado = definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, id, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, true)).valor();

        assertThat(encerrado.situacao()).isEqualTo("encerrado");
        assertThat(encerrado.fim()).isEqualTo(hoje.toString());
        assertThat(agenda(hoje.plusDays(60)).atrasados()).extracting(ItemAgenda::nome).contains("Streaming");
        assertThat(agenda(hoje.plusDays(60)).vencimentos()).extracting(ItemAgenda::nome).doesNotContain("Streaming");
        assertThat(listar.executar(chamador, new ListarCompromissos.Entrada(null, null, null, null, null)).compromissos()).isEmpty();
        assertThat(listar.executar(chamador, new ListarCompromissos.Entrada(null, null, null, null, true)).compromissos()).hasSize(1);
    }

    // ---- conta conectada ----

    @Test
    void contextoMostraAContaConectadaEAMarcaDeTeste() {
        Contexto.ContaConectada real = obterContexto.executar(chamador).contaConectada();
        assertThat(real.usuario()).isEqualTo("gui");
        assertThat(real.cliente()).isEqualTo("claude-ai");
        assertThat(real.contaDeTeste()).isFalse();
        assertThat(real.aviso()).isNull();

        Contexto.ContaConectada teste = obterContexto.executar(new Chamador(dono, "chatgpt", Set.of("fin:read"), "aceite")).contaConectada();
        assertThat(teste.contaDeTeste()).isTrue();
        assertThat(teste.aviso()).contains("teste");
    }

    // ---- apoio ----

    private ResultadoEscrita<ConferenciaRegistrada> conferirConta(String conta, String saldo, String resolucao) {
        return conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, conta, null, saldo, null, resolucao, null));
    }

    private CompromissoVisto compromisso(String nome, String direcao, String tipo, String valor, Integer parcelas, Integer pagasAntes, String primeiro,
            String conta, String contraparte) {
        return definirCompromisso.executar(chamador, entrada(null, nome, direcao, tipo, valor, parcelas, pagasAntes, primeiro, conta, contraparte))
                .valor();
    }

    private DefinirCompromisso.Entrada entrada(String ref, String nome, String direcao, String tipo, String valor, Integer parcelas,
            Integer pagasAntes, String primeiro, String conta, String contraparte) {
        return new DefinirCompromisso.Entrada(chave(), null, ref, direcao, tipo, nome, null, valor, null, parcelas, pagasAntes, null, primeiro, null,
                contraparte, null, null, conta, null, null, null, null);
    }

    private ResultadoEscrita<LancamentoRegistrado> pagar(String valor, String compromisso, String ocorrencia) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Pagamento", "Pix", "conversa", null, "Moradia",
                null, null, null, null, null, null, true, null, null, null, null, null, null, null, compromisso, ocorrencia));
    }

    private ResultadoEscrita<LancamentoRegistrado> noCartao(String valor, String descricao, int parcelas) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, descricao, "Nubank", "conversa", null, "Moradia",
                null, null, null, null, null, null, true, null, null, parcelas, null));
    }

    private CompromissoVisto um(String id) {
        return (CompromissoVisto) resolver.resolver(chamador, id);
    }

    private Agenda agenda(LocalDate ate) {
        return obterAgenda.executar(chamador, new ObterAgenda.Entrada(null, ate.toString(), null));
    }

    private Contexto.ContaContexto contaNoContexto(String nome) {
        return obterContexto.executar(chamador).contas().stream().filter(c -> c.nome().equals(nome)).findFirst().orElseThrow();
    }

    private ResumoVisto resumoDoMes() {
        return resumir.executar(chamador, new ResumirPeriodo.Entrada(null, null, hoje.toString().substring(0, 7), null, null, null, null, null)).resumo();
    }

    private long lancamentos() {
        return banco.fetchValue("select count(*) from lancamento where dono_id = ?", dono) instanceof Long n ? n : -1;
    }

    private void conta(String tipo, String nome, String saldo, String data) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, data, null));
    }

    private void confirmar(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }

    private static ErroTipado erro(Runnable chamada) {
        return catchThrowableOfType(ErroTipado.class, chamada::run);
    }
}
