package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/**
 * Se um lançamento nasceu no sistema ou veio de fora. Não é a {@link OrigemLancamento}, que diz o canal.
 * Depois do marco de uma conta, o fato entra nela; antes, é exógeno e não mexe nela.
 */
public enum Procedencia {
    ENDOGENO,
    EXOGENO;

    /** A data do marco já é coberta: só o que vem antes dele é exógeno. */
    public static Procedencia de(LocalDate data, LocalDate marco) {
        return marco != null && data.isBefore(marco) ? EXOGENO : ENDOGENO;
    }

    /** Linhas anteriores à coluna: só a abertura veio de fora. */
    public static Procedencia legado(TipoLancamento tipo) {
        return tipo == TipoLancamento.SALDO_ABERTURA ? EXOGENO : ENDOGENO;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Procedencia> deCodigo(String codigo) {
        for (Procedencia procedencia : values()) {
            if (procedencia.codigo().equals(codigo)) {
                return Optional.of(procedencia);
            }
        }
        return Optional.empty();
    }
}
