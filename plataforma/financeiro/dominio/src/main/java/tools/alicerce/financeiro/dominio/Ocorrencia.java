package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Uma ocorrência de compromisso, calculada.
 *
 * @param vencimentoOriginal o vencimento pela regra
 * @param vencimento o vencimento que vale: o novo, se foi adiada
 * @param lancamento o último pagamento vinculado; nulo se nenhum vale
 * @param valorPagoCentavos a soma dos pagamentos vinculados que valem; nulo se não há nenhum
 * @param dataPagamento a data do último pagamento; nula se não há nenhum
 * @param diferencaCentavos pago menos previsto, só na quitada (paga); nula nas demais
 * @param valorRestanteCentavos o que falta pagar, só em aberto (prevista ou atrasada); nulo nas demais
 * @param pagamentos os pagamentos vinculados que valem, na ordem em que foram vinculados
 */
public record Ocorrencia(int numero, LocalDate vencimentoOriginal, LocalDate vencimento, long valorPrevistoCentavos,
        SituacaoOcorrencia situacao, boolean adiada, UUID lancamento, Long valorPagoCentavos, LocalDate dataPagamento,
        Long diferencaCentavos, String observacao, Long valorRestanteCentavos, List<PagamentoOcorrencia> pagamentos) {

    public Ocorrencia {
        pagamentos = List.copyOf(pagamentos);
    }

    /** Em aberto com parte já paga. */
    public boolean pagaEmParte() {
        return situacao.emAberto() && !pagamentos.isEmpty();
    }

    /** Um pagamento vinculado à ocorrência. */
    public record PagamentoOcorrencia(UUID lancamento, long valorCentavos, LocalDate data) {}
}
