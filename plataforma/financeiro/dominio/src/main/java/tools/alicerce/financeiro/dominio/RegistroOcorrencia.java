package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma gravação de ocorrência que ainda vale: de operação aplicada e, se paga, com lançamento vigente. Uma
 * ocorrência pode ter várias (pagamentos parciais, adiamento, pulo); o estado dela sai de todas, em ordem
 * ({@link CalculoCompromisso#ocorrencia}).
 *
 * @param valorPagoCentavos o total do lançamento de pagamento; nulo se não é pagamento
 * @param dataPagamento a data do lançamento de pagamento; nula se não é pagamento
 * @param novaData o novo vencimento de uma ocorrência adiada; nula nas demais
 */
public record RegistroOcorrencia(int numero, Situacao situacao, UUID lancamento, Long valorPagoCentavos, LocalDate dataPagamento,
        LocalDate novaData, String observacao) {

    /** O que se grava de uma ocorrência. */
    public enum Situacao {
        PAGA,
        PULADA,
        ADIADA;

        public String codigo() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
