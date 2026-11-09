package tools.alicerce.financeiro.dominio;

import java.util.UUID;

/**
 * Onde uma parcela cai.
 *
 * @param conta o cartão, ou Saldo de abertura se a parcela não vira dívida nova
 * @param fatura a fatura que cobra a parcela; nula fora do cartão
 * @param faturaAbertura a fatura cuja abertura já contém a parcela; nula se não contém
 */
public record DestinoParcela(UUID conta, UUID fatura, UUID faturaAbertura) {

    public static DestinoParcela noCartao(UUID cartao, UUID fatura) {
        return new DestinoParcela(cartao, fatura, null);
    }

    /** Vencida antes do marco: já foi paga, e nenhuma fatura nasce para ela. */
    public static DestinoParcela anterior(UUID saldoDeAbertura) {
        return new DestinoParcela(saldoDeAbertura, null, null);
    }

    /** Já está dentro da abertura desta fatura: não muda a dívida. */
    public static DestinoParcela naAbertura(UUID saldoDeAbertura, UUID fatura) {
        return new DestinoParcela(saldoDeAbertura, null, fatura);
    }
}
