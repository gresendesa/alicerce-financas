package tools.alicerce.financeiro.dominio;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * O que uma imagem ligada a um lançamento é. O comprovante prova o pagamento; a foto do que foi
 * comprado é só lembrança, e nunca vai como prova. O padrão é comprovante.
 */
public enum NaturezaImagem {
    COMPROVANTE,
    FOTO;

    /** O nome gravado em {@code comprovante.natureza} e usado nas saídas. */
    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Vazio se o texto não é uma natureza conhecida. */
    public static Optional<NaturezaImagem> ler(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String limpo = texto.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(n -> n.codigo().equals(limpo)).findFirst();
    }

    public static NaturezaImagem deCodigo(String codigo) {
        return ler(codigo).orElseThrow();
    }
}
