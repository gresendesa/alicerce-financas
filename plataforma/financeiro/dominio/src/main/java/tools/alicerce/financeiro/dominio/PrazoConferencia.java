package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Quando a conferência de uma conta vence e como fica uma conferência. Conta conferida há mais de 30 dias
 * está vencida; conta nunca conferida vence 30 dias depois do marco.
 */
public final class PrazoConferencia {

    public static final int DIAS = 30;

    /** A situação de uma conferência, derivada da diferença e do ajuste. */
    public enum Situacao {
        /** O saldo informado bate com o calculado. */
        CONFERIDA,
        /** Há diferença, e ela ficou para o dono explicar. */
        PENDENTE,
        /** Há diferença, e o dono a aceitou como ajuste. */
        AJUSTADA;

        public String codigo() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Situacao de(long diferencaCentavos, boolean ajustada) {
            if (diferencaCentavos == 0) {
                return CONFERIDA;
            }
            return ajustada ? AJUSTADA : PENDENTE;
        }
    }

    private PrazoConferencia() {}

    /** O último dia em que a conferência ainda vale. @param ultima a data da última conferência, ou nula */
    public static LocalDate valeAte(LocalDate ultima, LocalDate marco) {
        LocalDate base = ultima != null ? ultima : marco;
        return base == null ? null : base.plusDays(DIAS);
    }

    public static boolean vencida(LocalDate hoje, LocalDate ultima, LocalDate marco) {
        LocalDate ate = valeAte(ultima, marco);
        return ate != null && hoje.isAfter(ate);
    }
}
