package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Um ciclo de um cartão.
 *
 * @param mesFechamento o mês em que a fatura fecha, que identifica o ciclo
 * @param fechaEm o último dia de compras da fatura
 * @param venceEm quando a fatura vence
 */
public record CicloFatura(YearMonth mesFechamento, LocalDate fechaEm, LocalDate venceEm) {

    /** O mês do vencimento, como {@code 2026-11}: é ele que nomeia a fatura. */
    public String periodo() {
        return YearMonth.from(venceEm).toString();
    }
}
