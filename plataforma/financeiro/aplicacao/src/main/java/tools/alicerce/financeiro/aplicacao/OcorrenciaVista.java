package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Uma ocorrência de compromisso nas saídas.
 *
 * @param parcela {@code 3/12} em parcelado; o número da ocorrência nos demais
 * @param vencimento o que vale: o novo, se foi adiada
 * @param vencimentoOriginal só se foi adiada
 * @param situacao {@code paga}, {@code paga_antes}, {@code pulada}, {@code atrasada} ou {@code prevista}
 * @param lancamento o último pagamento vinculado; ausente se não há nenhum
 * @param dataPagamento a data do último pagamento
 * @param valorPago a soma dos pagamentos vinculados, pelo valor real; ausente se não há nenhum
 * @param diferenca pago menos previsto, só na quitada ({@code paga})
 * @param faltaNaOcorrencia quanto falta desta ocorrência, só em aberto ({@code prevista} ou {@code atrasada}); menor que o previsto
 *     se já foi paga em parte
 * @param pagamentos cada pagamento vinculado, só quando há mais de um
 */
public record OcorrenciaVista(int numero, String parcela, String vencimento, String vencimentoOriginal, String valorPrevisto,
        String situacao, String lancamento, String urlLancamento, String dataPagamento, String valorPago, String diferenca,
        String observacao, String faltaNaOcorrencia, List<PagamentoVisto> pagamentos) {

    /** Um dos pagamentos de uma ocorrência paga em mais de uma vez. */
    public record PagamentoVisto(String lancamento, String url, String data, String valor) {}
}
