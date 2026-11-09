package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** De quanto em quanto tempo um compromisso vence. */
public enum FrequenciaCompromisso {
    MENSAL,
    ANUAL;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<FrequenciaCompromisso> deCodigo(String codigo) {
        for (FrequenciaCompromisso frequencia : values()) {
            if (frequencia.codigo().equals(codigo)) {
                return Optional.of(frequencia);
            }
        }
        return Optional.empty();
    }
}
