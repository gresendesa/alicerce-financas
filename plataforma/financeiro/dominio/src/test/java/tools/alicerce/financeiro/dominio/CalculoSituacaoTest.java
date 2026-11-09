package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.alicerce.financeiro.dominio.CalculoSituacao.Severidade;

class CalculoSituacaoTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-11-04");

    private static LocalDate d(String texto) {
        return LocalDate.parse(texto);
    }

    private static Ocorrencia ocorrencia(SituacaoOcorrencia situacao, String vencimento, Long restante, long pago) {
        List<Ocorrencia.PagamentoOcorrencia> pagamentos = pago == 0 ? List.of()
                : List.of(new Ocorrencia.PagamentoOcorrencia(java.util.UUID.randomUUID(), pago, d(vencimento)));
        return new Ocorrencia(1, d(vencimento), d(vencimento), 15000, situacao, false, null, pago == 0 ? null : pago, null, null, null,
                restante, pagamentos);
    }

    // compromisso_atrasado

    @Test
    void ocorrenciaAtrasadaDisparaComAvisoAteTrintaDiasECriticoDepois() {
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.ATRASADA, "2026-11-03", 15000L, 0), HOJE))
                .contains(Severidade.AVISO);
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.ATRASADA, "2026-10-05", 15000L, 0), HOJE))
                .as("30 dias de atraso ainda é aviso").contains(Severidade.AVISO);
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.ATRASADA, "2026-10-04", 15000L, 0), HOJE))
                .as("31 dias").contains(Severidade.CRITICO);
    }

    @Test
    void pagaEmParteAtrasadaDisparaPeloRestante() {
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.ATRASADA, "2026-11-01", 5000L, 10000), HOJE))
                .contains(Severidade.AVISO);
    }

    @Test
    void ocorrenciaPrevistaPagaOuPuladaNaoDispara() {
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.PREVISTA, "2026-11-04", 15000L, 0), HOJE)).isEmpty();
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.PAGA, "2026-10-01", null, 15000), HOJE)).isEmpty();
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.PULADA, "2026-10-01", null, 0), HOJE)).isEmpty();
        assertThat(CalculoSituacao.compromissoAtrasado(ocorrencia(SituacaoOcorrencia.PAGA_ANTES, "2026-01-01", null, 0), HOJE)).isEmpty();
    }

    @Test
    void atrasoVemDoCalculoDeCompromisso() {
        Compromisso acordo = new Compromisso(java.util.UUID.randomUUID(), DirecaoCompromisso.SAIDA, TipoCompromisso.PARCELADO, "Acordo",
                List.of(), null, null, 15000, null, "BRL", FrequenciaCompromisso.MENSAL, d("2026-10-10"), 2, 0, null, null, null, null, null,
                null, false, 1);
        Ocorrencia primeira = CalculoCompromisso.ocorrencia(acordo, 1, null, HOJE);
        Ocorrencia segunda = CalculoCompromisso.ocorrencia(acordo, 2, null, HOJE);

        assertThat(CalculoSituacao.compromissoAtrasado(primeira, HOJE)).contains(Severidade.AVISO);
        assertThat(CalculoSituacao.compromissoAtrasado(segunda, HOJE)).isEmpty();
    }

    // fatura_vencendo

    @Test
    void faturaComRestanteDisparaAteCincoDiasECriticaHojeOuAmanha() {
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-04"), 6000, HOJE)).contains(Severidade.CRITICO);
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-05"), 6000, HOJE)).contains(Severidade.CRITICO);
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-06"), 6000, HOJE)).contains(Severidade.AVISO);
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-09"), 6000, HOJE)).as("5 dias").contains(Severidade.AVISO);
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-10"), 6000, HOJE)).as("6 dias").isEmpty();
    }

    @Test
    void faturaPagaNaoDisparaEVencidaComRestanteECritica() {
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-06"), 0, HOJE)).isEmpty();
        assertThat(CalculoSituacao.faturaVencendo(d("2026-11-06"), -100, HOJE)).as("pago a mais").isEmpty();
        assertThat(CalculoSituacao.faturaVencendo(d("2026-10-26"), 6000, HOJE)).contains(Severidade.CRITICO);
    }

    // conferências

    @Test
    void conferenciaPendenteEAvisoESoNaPendente() {
        assertThat(CalculoSituacao.conferenciaPendente(PrazoConferencia.Situacao.PENDENTE)).contains(Severidade.AVISO);
        assertThat(CalculoSituacao.conferenciaPendente(PrazoConferencia.Situacao.CONFERIDA)).isEmpty();
        assertThat(CalculoSituacao.conferenciaPendente(PrazoConferencia.Situacao.AJUSTADA)).isEmpty();
        assertThat(CalculoSituacao.conferenciaPendente(null)).as("nunca conferida").isEmpty();
    }

    @Test
    void conferenciaAntigaEInfoDepoisDoMesmoPrazoDaAgenda() {
        assertThat(CalculoSituacao.conferenciaAntiga(HOJE, d("2026-10-05"), d("2026-01-01"))).as("30 dias ainda vale").isEmpty();
        assertThat(CalculoSituacao.conferenciaAntiga(HOJE, d("2026-10-04"), d("2026-01-01"))).contains(Severidade.INFO);
        assertThat(CalculoSituacao.conferenciaAntiga(HOJE, null, d("2026-10-01"))).as("nunca conferida, pelo marco").contains(Severidade.INFO);
        assertThat(CalculoSituacao.conferenciaAntiga(HOJE, null, d("2026-10-20"))).isEmpty();
    }

    // categoria_subindo

    @Test
    void mesesFechadosSaoOsTresAntesDoMesDeHoje() {
        assertThat(CalculoSituacao.mesesFechados(HOJE)).containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10));
        assertThat(CalculoSituacao.mesesFechados(d("2026-01-31"))).containsExactly(YearMonth.of(2025, 10), YearMonth.of(2025, 11),
                YearMonth.of(2025, 12));
    }

    @Test
    void categoriaQueSobeMaisDeDezPorCentoEmCadaMesDispara() {
        var tendencia = CalculoSituacao.categoriaSubindo(List.of(10000L, 11500L, 13000L)).orElseThrow();

        assertThat(tendencia.saidasCentavos()).containsExactly(10000L, 11500L, 13000L);
        assertThat(tendencia.altas()).containsExactly(new BigDecimal("15.00"), new BigDecimal("13.04"));
        assertThat(CalculoSituacao.severidadeCategoria()).isEqualTo(Severidade.INFO);
    }

    @Test
    void dezPorCentoExatoNaoDispara() {
        assertThat(CalculoSituacao.categoriaSubindo(List.of(10000L, 11000L, 12100L))).isEmpty();
        assertThat(CalculoSituacao.categoriaSubindo(List.of(10000L, 11001L, 12102L))).isPresent();
    }

    @Test
    void mesSemGastoCategoriaNovaEPicoIsoladoNaoDisparam() {
        assertThat(CalculoSituacao.categoriaSubindo(List.of(0L, 11500L, 13000L))).as("mês sem gasto").isEmpty();
        assertThat(CalculoSituacao.categoriaSubindo(List.of(10000L, 0L, 13000L))).as("mês do meio sem gasto").isEmpty();
        assertThat(CalculoSituacao.categoriaSubindo(List.of(11500L, 13000L))).as("categoria nova").isEmpty();
        assertThat(CalculoSituacao.categoriaSubindo(List.of(10000L, 30000L, 12000L))).as("pico isolado").isEmpty();
        assertThat(CalculoSituacao.categoriaSubindo(List.of(10000L, 10500L, 20000L))).as("só o último mês subiu").isEmpty();
        assertThat(CalculoSituacao.categoriaSubindo(List.of(13000L, 11500L, 10000L))).as("caindo").isEmpty();
    }

    @Test
    void severidadeSaiNaOrdemDoMaisGrave() {
        assertThat(Severidade.values()).containsExactly(Severidade.CRITICO, Severidade.AVISO, Severidade.INFO);
        assertThat(Severidade.AVISO.codigo()).isEqualTo("aviso");
        assertThat(CalculoSituacao.Codigo.CATEGORIA_SUBINDO.codigo()).isEqualTo("categoria_subindo");
    }
}
