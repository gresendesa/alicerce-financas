package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalculoCompromissoTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-11-02");

    private static Compromisso parcelado(String primeiro, int parcelas, long valor, Long total, int pagasAntes) {
        return new Compromisso(UUID.randomUUID(), DirecaoCompromisso.SAIDA, TipoCompromisso.PARCELADO, "IPTU", List.of(), "Prefeitura",
                "PARC-123", valor, total, "BRL", FrequenciaCompromisso.MENSAL, LocalDate.parse(primeiro), parcelas, pagasAntes, null, null,
                null, null, null, null, false, 1);
    }

    private static Compromisso recorrente(String primeiro, String fim) {
        return new Compromisso(UUID.randomUUID(), DirecaoCompromisso.SAIDA, TipoCompromisso.RECORRENTE, "Aluguel", List.of(), null, null,
                150000, null, "BRL", FrequenciaCompromisso.MENSAL, LocalDate.parse(primeiro), null, 0, fim == null ? null : LocalDate.parse(fim),
                null, null, null, null, null, false, 1);
    }

    @Test
    void vencimentoNoDiaDoPrimeiroEDiaMaiorQueOMesCaiNoUltimo() {
        Compromisso c = parcelado("2026-01-31", 4, 10000, null, 0);

        assertThat(c.vencimento(1)).isEqualTo("2026-01-31");
        assertThat(c.vencimento(2)).isEqualTo("2026-02-28");
        assertThat(c.vencimento(3)).isEqualTo("2026-03-31");
        assertThat(c.existe(4)).isTrue();
        assertThat(c.existe(5)).isFalse();
    }

    @Test
    void anualRepeteNoMesmoDiaEFevereiroBissextoCaiNoUltimoDia() {
        Compromisso c = new Compromisso(UUID.randomUUID(), DirecaoCompromisso.SAIDA, TipoCompromisso.RECORRENTE, "IPVA", List.of(), null, null,
                90000, null, "BRL", FrequenciaCompromisso.ANUAL, LocalDate.parse("2028-02-29"), null, 0, null, null, null, null, null, null, false, 1);

        assertThat(c.vencimento(2)).isEqualTo("2029-02-28");
        assertThat(c.numeroNoMes(java.time.YearMonth.parse("2030-02"))).isEqualTo(3);
        assertThat(c.numeroNoMes(java.time.YearMonth.parse("2030-03"))).isZero();
    }

    @Test
    void parceladoPeloTotalPoeASobraNaPrimeira() {
        Compromisso c = parcelado("2026-11-10", 3, 3333, 10000L, 0);

        assertThat(c.valorCentavos(1)).isEqualTo(3334);
        assertThat(c.valorCentavos(2)).isEqualTo(3333);
        assertThat(c.valorCentavos(1) + c.valorCentavos(2) + c.valorCentavos(3)).isEqualTo(10000);
    }

    @Test
    void ocorrenciasSaemDaRegraEDoQueFoiGravado() {
        Compromisso c = parcelado("2026-09-10", 6, 15000, null, 1);
        UUID pagamento = UUID.randomUUID();
        Map<Integer, List<RegistroOcorrencia>> registros = Map.of(
                2, List.of(new RegistroOcorrencia(2, RegistroOcorrencia.Situacao.PAGA, pagamento, 16000L, LocalDate.parse("2026-10-09"), null, null)),
                3, List.of(new RegistroOcorrencia(3, RegistroOcorrencia.Situacao.ADIADA, null, null, null, LocalDate.parse("2026-11-20"), "combinado")),
                4, List.of(new RegistroOcorrencia(4, RegistroOcorrencia.Situacao.PULADA, null, null, null, null, null)));

        List<Ocorrencia> todas = CalculoCompromisso.ocorrencias(c, registros, HOJE, null);

        assertThat(todas).extracting(Ocorrencia::situacao).containsExactly(SituacaoOcorrencia.PAGA_ANTES, SituacaoOcorrencia.PAGA,
                SituacaoOcorrencia.PREVISTA, SituacaoOcorrencia.PULADA, SituacaoOcorrencia.PREVISTA, SituacaoOcorrencia.PREVISTA);
        assertThat(todas.get(1).diferencaCentavos()).isEqualTo(1000);
        assertThat(todas.get(2).vencimento()).isEqualTo("2026-11-20");
        assertThat(todas.get(2).adiada()).isTrue();

        CalculoCompromisso.Saldo saldo = CalculoCompromisso.saldo(c, todas);
        assertThat(saldo.pagas()).isEqualTo(2);
        assertThat(saldo.puladas()).isEqualTo(1);
        assertThat(saldo.emAberto()).isEqualTo(3);
        assertThat(saldo.valorPagoCentavos()).isEqualTo(16000);
        assertThat(saldo.valorRestanteCentavos()).isEqualTo(45000);
        assertThat(saldo.total()).isEqualTo(6);
        assertThat(saldo.proxima()).get().extracting(Ocorrencia::numero).isEqualTo(3);
    }

    @Test
    void vencidaSemPagamentoEstaAtrasadaEHojeAindaNao() {
        Compromisso c = recorrente("2026-10-02", null);

        List<Ocorrencia> ate = CalculoCompromisso.ocorrencias(c, Map.of(), HOJE, LocalDate.parse("2026-12-31"));

        assertThat(ate).extracting(Ocorrencia::situacao).containsExactly(SituacaoOcorrencia.ATRASADA, SituacaoOcorrencia.PREVISTA,
                SituacaoOcorrencia.PREVISTA);
        CalculoCompromisso.Saldo saldo = CalculoCompromisso.saldo(c, ate);
        assertThat(saldo.valorRestanteCentavos()).isNull();
        assertThat(saldo.valorAtrasadoCentavos()).isEqualTo(150000);
        assertThat(saldo.atrasadas()).isEqualTo(1);
    }

    @Test
    void recorrenteSemFimPrecisaDeLimiteEComFimPara() {
        assertThatThrownBy(() -> CalculoCompromisso.ocorrencias(recorrente("2026-10-02", null), Map.of(), HOJE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CalculoCompromisso.ocorrencias(recorrente("2026-10-02", "2027-01-15"), Map.of(), HOJE, null)).hasSize(4);
    }

    @Test
    void pagaAdiantadaForaDoLimiteContinuaAparecendo() {
        Compromisso c = recorrente("2026-10-02", null);
        Map<Integer, List<RegistroOcorrencia>> registros = Map.of(
                5, List.of(new RegistroOcorrencia(5, RegistroOcorrencia.Situacao.PAGA, UUID.randomUUID(), 150000L, HOJE, null, null)));

        List<Ocorrencia> lista = CalculoCompromisso.ocorrencias(c, registros, HOJE, LocalDate.parse("2026-11-30"));

        assertThat(lista).extracting(Ocorrencia::numero).containsExactly(1, 2, 5);
    }

    // ---- pagamento parcial ----

    private static RegistroOcorrencia paga(int numero, long centavos, String data) {
        return new RegistroOcorrencia(numero, RegistroOcorrencia.Situacao.PAGA, UUID.randomUUID(), centavos, LocalDate.parse(data), null, null);
    }

    private static RegistroOcorrencia pulada(int numero) {
        return new RegistroOcorrencia(numero, RegistroOcorrencia.Situacao.PULADA, null, null, null, null, "acordo");
    }

    @Test
    void doisPagamentosQuitamAParcelaEEntreElesFaltaORestante() {
        Compromisso c = parcelado("2026-11-10", 3, 15000, null, 0);
        LocalDate dia15 = LocalDate.parse("2026-11-15");

        Ocorrencia emParte = CalculoCompromisso.ocorrencia(c, 1, List.of(paga(1, 10000, "2026-11-10")), dia15);
        assertThat(emParte.situacao()).isEqualTo(SituacaoOcorrencia.ATRASADA);
        assertThat(emParte.pagaEmParte()).isTrue();
        assertThat(emParte.valorPagoCentavos()).isEqualTo(10000);
        assertThat(emParte.valorRestanteCentavos()).isEqualTo(5000);
        assertThat(emParte.diferencaCentavos()).isNull();

        CalculoCompromisso.Saldo entre = CalculoCompromisso.saldo(c, CalculoCompromisso.ocorrencias(c,
                Map.of(1, List.of(paga(1, 10000, "2026-11-10"))), dia15, null));
        assertThat(entre.emAberto()).isEqualTo(3);
        assertThat(entre.pagasEmParte()).isEqualTo(1);
        assertThat(entre.atrasadas()).isEqualTo(1);
        assertThat(entre.valorAtrasadoCentavos()).isEqualTo(5000);
        assertThat(entre.valorPagoCentavos()).isEqualTo(10000);
        assertThat(entre.valorRestanteCentavos()).isEqualTo(35000);

        Ocorrencia quitada = CalculoCompromisso.ocorrencia(c, 1, List.of(paga(1, 10000, "2026-11-10"), paga(1, 5000, "2026-11-20")),
                LocalDate.parse("2026-11-20"));
        assertThat(quitada.situacao()).isEqualTo(SituacaoOcorrencia.PAGA);
        assertThat(quitada.valorPagoCentavos()).isEqualTo(15000);
        assertThat(quitada.diferencaCentavos()).isZero();
        assertThat(quitada.valorRestanteCentavos()).isNull();
        assertThat(quitada.pagamentos()).hasSize(2);
        assertThat(quitada.dataPagamento()).isEqualTo("2026-11-20");
    }

    @Test
    void antesDoVencimentoAParcelaPagaEmParteContinuaPrevista() {
        Compromisso c = parcelado("2026-11-10", 3, 15000, null, 0);

        Ocorrencia o = CalculoCompromisso.ocorrencia(c, 1, List.of(paga(1, 10000, "2026-11-05")), LocalDate.parse("2026-11-10"));

        assertThat(o.situacao()).isEqualTo(SituacaoOcorrencia.PREVISTA);
        assertThat(o.valorRestanteCentavos()).isEqualTo(5000);
    }

    @Test
    void pagamentoAcimaDoQueFaltaQuitaEMostraADiferencaSemMexerNaProxima() {
        Compromisso c = parcelado("2026-11-10", 2, 15000, null, 0);
        Map<Integer, List<RegistroOcorrencia>> registros = Map.of(1, List.of(paga(1, 10000, "2026-11-10"), paga(1, 8000, "2026-11-20")));

        List<Ocorrencia> todas = CalculoCompromisso.ocorrencias(c, registros, LocalDate.parse("2026-11-20"), null);

        assertThat(todas.get(0).situacao()).isEqualTo(SituacaoOcorrencia.PAGA);
        assertThat(todas.get(0).diferencaCentavos()).isEqualTo(3000);
        assertThat(todas.get(1).valorRestanteCentavos()).isEqualTo(15000);
        assertThat(CalculoCompromisso.saldo(c, todas).valorRestanteCentavos()).isEqualTo(15000);
    }

    @Test
    void pularAParcelaPagaEmParteEncerraOQueFaltaEOPagoContinuaContando() {
        Compromisso c = parcelado("2026-10-10", 2, 15000, null, 0);
        Map<Integer, List<RegistroOcorrencia>> registros = Map.of(1, List.of(paga(1, 10000, "2026-10-10"), pulada(1)));

        List<Ocorrencia> todas = CalculoCompromisso.ocorrencias(c, registros, HOJE, null);

        assertThat(todas.get(0).situacao()).isEqualTo(SituacaoOcorrencia.PULADA);
        assertThat(todas.get(0).valorRestanteCentavos()).isNull();
        CalculoCompromisso.Saldo saldo = CalculoCompromisso.saldo(c, todas);
        assertThat(saldo.valorPagoCentavos()).isEqualTo(10000);
        assertThat(saldo.valorRestanteCentavos()).isEqualTo(15000);
        assertThat(saldo.valorAtrasadoCentavos()).isZero();
    }

    @Test
    void adiarAParcelaPagaEmParteMudaSoOVencimentoDoQueFalta() {
        Compromisso c = parcelado("2026-10-10", 2, 15000, null, 0);
        RegistroOcorrencia adiada = new RegistroOcorrencia(1, RegistroOcorrencia.Situacao.ADIADA, null, null, null,
                LocalDate.parse("2026-11-30"), null);

        Ocorrencia o = CalculoCompromisso.ocorrencia(c, 1, List.of(paga(1, 10000, "2026-10-10"), adiada), HOJE);

        assertThat(o.situacao()).isEqualTo(SituacaoOcorrencia.PREVISTA);
        assertThat(o.adiada()).isTrue();
        assertThat(o.vencimento()).isEqualTo("2026-11-30");
        assertThat(o.valorRestanteCentavos()).isEqualTo(5000);
    }

    @Test
    void semUmDosPagamentosAParcelaReabrePeloValorDele() {
        // Desfazer ou anular um pagamento tira a gravação dele da lista: o restante sai da conta de novo (decisão 3).
        Compromisso c = parcelado("2026-11-10", 1, 15000, null, 0);

        Ocorrencia o = CalculoCompromisso.ocorrencia(c, 1, List.of(paga(1, 5000, "2026-11-20")), LocalDate.parse("2026-11-21"));

        assertThat(o.situacao()).isEqualTo(SituacaoOcorrencia.ATRASADA);
        assertThat(o.valorRestanteCentavos()).isEqualTo(10000);
    }
}
