package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.Locale;
import tools.alicerce.convencoes.Dinheiro;

/** A situação de uma fatura, sempre derivada: nunca é gravada, e desfazer um pagamento a corrige sozinho. */
public enum SituacaoFatura {
    ABERTA,
    FECHADA,
    PARCIALMENTE_PAGA,
    PAGA;

    /** Os pagamentos mandam; sem nenhum, a data. O dia do fechamento ainda é aberto. */
    public static SituacaoFatura de(LocalDate hoje, LocalDate fechaEm, Dinheiro total, Dinheiro pago) {
        if (!pago.ehZero()) {
            return pago.compareTo(total) >= 0 ? PAGA : PARCIALMENTE_PAGA;
        }
        return hoje.isAfter(fechaEm) ? FECHADA : ABERTA;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }
}
