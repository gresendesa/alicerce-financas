package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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
import tools.alicerce.financeiro.aplicacao.Agenda;
import tools.alicerce.financeiro.aplicacao.CompromissoVisto;
import tools.alicerce.financeiro.aplicacao.ConferirSaldo;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.ItemAgenda;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.ListarCompromissos;
import tools.alicerce.financeiro.aplicacao.ObterAgenda;
import tools.alicerce.financeiro.aplicacao.ObterSituacao;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.ResumoVisto;
import tools.alicerce.financeiro.aplicacao.Situacao;
import tools.alicerce.financeiro.aplicacao.Situacao.SinalVisto;
import tools.alicerce.financeiro.dominio.CartaoCredito;

/** Testes de aceite (situacao), nos casos de uso, contra o banco. As datas saem de hoje. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class SituacaoTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirCompromisso definirCompromisso;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired CorrigirLancamento corrigir;
    @Autowired ConferirSaldo conferir;
    @Autowired ObterAgenda obterAgenda;
    @Autowired ListarCompromissos listarCompromissos;
    @Autowired ResumirPeriodo resumir;
    @Autowired ObterSituacao situacao;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;

    @BeforeEach
    void dono() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:profile"), "gui");
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    @Test
    void semNadaAApontarAListaVemVaziaEAMensagemDizIsso() {
        conta("corrente", "Itaú", "1000.00", hoje.toString());
        long operacoes = operacoes();

        Situacao s = situacao.executar(chamador);

        assertThat(s.sinais()).isEmpty();
        assertThat(s.mensagem()).startsWith("Nenhum sinal");
        assertThat(s.contagem()).isEqualTo(new Situacao.Contagem(0, 0, 0));
        assertThat(s.dataReferencia()).isEqualTo(hoje.toString());
        assertThat(s.ressalvas()).anySatisfy(r -> assertThat(r).contains("categoria_subindo não foi avaliado", "Itaú", hoje.toString()));
        assertThat(s.naoAvaliados()).extracting(Situacao.NaoAvaliado::codigo).containsExactly("reserva_abaixo_projetada", "parcelas_acima_teto",
                "poupanca_abaixo");
        assertThat(operacoes()).as("situacao não grava nada").isEqualTo(operacoes);
    }

    @Test
    void compromissoFaturaEConferenciasBatemComAgendaECompromissos() {
        conta("corrente", "Itaú", "1000.00", hoje.minusDays(5).toString());
        conta("carteira", "Carteira", "50.00", hoje.minusDays(60).toString());
        conta("categoria", "Dívidas", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        int[] dias = cartaoQueVenceEm(3);
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Nubank", null, null, null, null, null, null,
                new DadosCartao(dias[0], dias[1], "5000.00", "Itaú")));
        CompromissoVisto acordo = definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, "saida",
                "parcelado", "Acordo", null, "150.00", null, 3, null, null, hoje.minusDays(40).toString(), null, null, "ACORDO-1", null, "Itaú", null,
                null, null, null)).valor();
        gasto("100.00", hoje.toString(), "Pix", acordo.id(), "2");
        gasto("60.00", hoje.toString(), "Nubank", null, null);
        conferir.executar(chamador, new ConferirSaldo.Entrada(chave(), null, "Itaú", null, "800.00", null, null, null));
        long operacoes = operacoes();

        Situacao s = situacao.executar(chamador);

        assertThat(s.sinais()).extracting(SinalVisto::codigo, SinalVisto::severidade, SinalVisto::nome).containsExactly(
                tuple("compromisso_atrasado", "critico", "Acordo"),
                tuple("compromisso_atrasado", "aviso", "Acordo"),
                tuple("fatura_vencendo", "aviso", "Fatura Nubank " + new CartaoCredito(UUID.randomUUID(), dias[0], dias[1], null, null, 1)
                        .cicloDaCompra(hoje).periodo()),
                tuple("conferencia_pendente", "aviso", "Itaú"),
                tuple("conferencia_antiga", "info", "Carteira"));
        assertThat(s.contagem()).isEqualTo(new Situacao.Contagem(1, 3, 1));
        assertThat(s.mensagem()).isNull();
        assertThat(operacoes()).as("situacao não grava nada").isEqualTo(operacoes);

        // Os mesmos números da agenda e de listar_compromissos, no mesmo dia.
        Agenda agenda = obterAgenda.executar(chamador, new ObterAgenda.Entrada(null, null, null));
        List<SinalVisto> atrasos = s.sinais().stream().filter(x -> x.codigo().equals("compromisso_atrasado")).toList();
        assertThat(atrasos).extracting(SinalVisto::id, SinalVisto::url, SinalVisto::vencimento, SinalVisto::valor, SinalVisto::parcela)
                .containsExactlyInAnyOrderElementsOf(agenda.atrasados().stream().filter(i -> i.tipo().equals("compromisso"))
                        .map(i -> tuple(i.id(), i.url(), i.vencimento(), i.valor(), i.parcela())).toList());
        SinalVisto critico = atrasos.get(0);
        assertThat(critico.parcela()).isEqualTo("1/3");
        assertThat(critico.diasAtraso()).isEqualTo(40L);
        assertThat(critico.valor()).isEqualTo("150.00");
        assertThat(critico.url()).contains("/fin/");
        SinalVisto emParte = atrasos.get(1);
        assertThat(emParte.parcela()).isEqualTo("2/3");
        assertThat(emParte.valor()).isEqualTo("50.00");
        assertThat(emParte.valorPago()).isEqualTo("100.00");
        assertThat(emParte.valorPrevisto()).isEqualTo("150.00");
        CompromissoVisto listado = listarCompromissos.executar(chamador, new ListarCompromissos.Entrada("ACORDO-1", null, null, null, null))
                .compromissos().get(0);
        assertThat(listado.saldo().atrasadas()).isEqualTo(atrasos.size());

        ItemAgenda faturaNaAgenda = agenda.vencimentos().stream().filter(i -> i.tipo().equals("fatura")).findFirst().orElseThrow();
        SinalVisto fatura = s.sinais().get(2);
        assertThat(fatura.id()).isEqualTo(faturaNaAgenda.id()).startsWith("f-");
        assertThat(fatura.url()).isEqualTo(faturaNaAgenda.url());
        assertThat(fatura.valor()).isEqualTo(faturaNaAgenda.valor()).isEqualTo("60.00");
        assertThat(fatura.diasParaVencer()).isEqualTo(3L);

        assertThat(s.sinais().get(3).diferencaPendente()).isEqualTo("-100.00");
        assertThat(s.sinais().get(4).id()).startsWith("a-");
        assertThat(s.sinais().get(4).valeAte()).isEqualTo(hoje.minusDays(30).toString());
        assertThat(agenda.conferenciasVencidas()).extracting(c -> c.nome()).containsExactly("Carteira");
    }

    @Test
    void categoriaSubindoBateComOResumoPorCategoria() {
        conta("corrente", "Itaú", "5000.00", YearMonth.from(hoje).minusMonths(4).atDay(1).toString());
        conta("categoria", "Mercado", null, null);
        conta("categoria", "Lazer", null, null);
        conta("categoria", "Farmácia", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        List<YearMonth> meses = List.of(YearMonth.from(hoje).minusMonths(3), YearMonth.from(hoje).minusMonths(2), YearMonth.from(hoje).minusMonths(1));
        String[][] valores = {{"100.00", "115.00", "130.00"}, {"100.00", "300.00", "120.00"}, {"100.00", "120.00", "150.00"}};
        String[] categorias = {"Mercado", "Lazer", "Farmácia"};
        String ultimaFarmacia = null;
        for (int c = 0; c < categorias.length; c++) {
            for (int m = 0; m < meses.size(); m++) {
                ultimaFarmacia = gastoEm(valores[c][m], meses.get(m).atDay(10).toString(), categorias[c]).valor().id();
            }
        }
        // Mês de hoje não conta: só os meses fechados.
        gastoEm("10.00", hoje.withDayOfMonth(1).toString(), "Lazer");
        // A anulada sai do mês, como no resumo: Farmácia deixa de subir.
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, ultimaFarmacia, "anular", null, "Errei", null));

        Situacao s = situacao.executar(chamador);

        List<SinalVisto> categoria = s.sinais().stream().filter(x -> x.codigo().equals("categoria_subindo")).toList();
        assertThat(categoria).singleElement().satisfies(x -> {
            assertThat(x.nome()).isEqualTo("Mercado");
            assertThat(x.severidade()).isEqualTo("info");
            assertThat(x.alvo()).isEqualTo("categoria");
            assertThat(x.id()).startsWith("a-");
            assertThat(x.url()).contains("/fin/");
            assertThat(x.altas()).containsExactly("15.00", "13.04");
            assertThat(x.meses()).extracting(Situacao.MesVisto::periodo).containsExactly(meses.stream().map(YearMonth::toString).toArray(String[]::new));
            for (Situacao.MesVisto mes : x.meses()) {
                assertThat(mes.saidas()).isEqualTo(saidaNoResumo(mes.periodo(), "Mercado"));
            }
        });
        assertThat(saidaNoResumo(meses.get(2).toString(), "Farmácia")).isEqualTo("0.00");
        assertThat(s.ressalvas()).isNull();
    }

    // ---- apoio ----

    /** Dias de fechamento e vencimento em que a compra de hoje cai numa fatura que vence daqui a {@code dias} dias. */
    private int[] cartaoQueVenceEm(int dias) {
        for (int fechamento = 1; fechamento <= 28; fechamento++) {
            for (int vencimento = 1; vencimento <= 28; vencimento++) {
                if (vencimento != fechamento && new CartaoCredito(UUID.randomUUID(), fechamento, vencimento, null, null, 1).cicloDaCompra(hoje)
                        .venceEm().equals(hoje.plusDays(dias))) {
                    return new int[] {fechamento, vencimento};
                }
            }
        }
        throw new IllegalStateException("Nenhum cartão vence em " + dias + " dias a partir de " + hoje);
    }

    private String saidaNoResumo(String periodo, String categoria) {
        ResumoVisto r = resumir.calcularAgora(chamador, new ResumirPeriodo.Entrada(null, null, periodo, List.of("categoria"), null, null, null, false));
        return r.quebras().get(0).linhas().stream().filter(l -> l.nome().equals(categoria)).map(ResumoVisto.LinhaVista::saidas).findFirst()
                .orElse("0.00");
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String valor, String data, String meio, String compromisso, String ocorrencia) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Pagamento", meio, "conversa", data, "Dívidas",
                null, null, null, null, null, null, true, null, null, null, null, null, null, null, compromisso, ocorrencia));
    }

    private ResultadoEscrita<LancamentoRegistrado> gastoEm(String valor, String data, String categoria) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra", "Pix", "conversa", data, categoria,
                null, null, null, null, null, null, true, null, null, null, null, null, null, null, null, null));
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
