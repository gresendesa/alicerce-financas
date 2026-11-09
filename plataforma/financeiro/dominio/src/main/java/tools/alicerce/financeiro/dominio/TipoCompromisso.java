package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** Recorrente se repete até ser encerrado; parcelado tem um número fixo de parcelas; único vence uma vez. */
public enum TipoCompromisso {
    RECORRENTE,
    PARCELADO,
    UNICO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TipoCompromisso> deCodigo(String codigo) {
        for (TipoCompromisso tipo : values()) {
            if (tipo.codigo().equals(codigo)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
