package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** Para que lado o dinheiro de um compromisso vai: sai do dono ou entra para ele. */
public enum DirecaoCompromisso {
    ENTRADA,
    SAIDA;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DirecaoCompromisso> deCodigo(String codigo) {
        for (DirecaoCompromisso direcao : values()) {
            if (direcao.codigo().equals(codigo)) {
                return Optional.of(direcao);
            }
        }
        return Optional.empty();
    }
}
