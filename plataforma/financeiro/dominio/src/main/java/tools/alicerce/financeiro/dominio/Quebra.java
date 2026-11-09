package tools.alicerce.financeiro.dominio;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** Por onde um resumo de período pode ser quebrado. */
public enum Quebra {
    CATEGORIA,
    MEIO_PAGAMENTO,
    CONTRAPARTE,
    CONTA,
    AMBITO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<Quebra> deCodigo(String codigo) {
        return Arrays.stream(values()).filter(q -> q.codigo().equals(codigo)).findFirst();
    }

    public static String opcoes() {
        return Arrays.stream(values()).map(Quebra::codigo).collect(Collectors.joining(", "));
    }
}
