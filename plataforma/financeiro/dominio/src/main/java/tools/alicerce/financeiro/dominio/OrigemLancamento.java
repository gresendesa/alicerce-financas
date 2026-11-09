package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** De onde veio a informação do lançamento. */
public enum OrigemLancamento {
    CONVERSA,
    FOTO_NOTA,
    PRINT_APP;

    public static Optional<OrigemLancamento> deCodigo(String codigo) {
        for (OrigemLancamento origem : values()) {
            if (origem.name().toLowerCase(Locale.ROOT).equals(codigo)) {
                return Optional.of(origem);
            }
        }
        return Optional.empty();
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }
}
