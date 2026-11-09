package tools.alicerce.convencoes;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Endereço estável e legível de uma entidade: prefixo do tipo + 6 caracteres em base32 sem letras
 * ambíguas (alfabeto de Crockford). Formas: {@code t-8K3QZ2} (id curto), {@code fin:t-8K3QZ2} (id
 * completo, com o módulo) e {@code /fin/t/8K3QZ2} (caminho da URL). Cada módulo define seus prefixos.
 *
 * @param modulo o módulo dono da entidade, como {@code fin}
 * @param prefixo uma letra minúscula que identifica o tipo, como {@code t}
 * @param codigo os 6 caracteres, já normalizados
 */
public record ReferenciaCurta(String modulo, String prefixo, String codigo) {

    public static final String ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int TAMANHO = 6;

    private static final Pattern MODULO = Pattern.compile("[a-z]{2,8}");
    private static final Pattern PREFIXO = Pattern.compile("[a-z]");
    private static final Pattern CODIGO = Pattern.compile("[" + ALFABETO + "]{" + TAMANHO + "}");

    // Aceita o id curto, o id completo ou a URL colados pelo assistente.
    private static final Pattern TEXTO = Pattern.compile(
            "(?:(?:https?://[^/\\s]+)?/(?<moduloUrl>[a-z]{2,8})/(?<prefixoUrl>[a-z])/(?<codigoUrl>[0-9a-z]{6})/?)"
                    + "|(?:(?:(?<modulo>[a-z]{2,8}):)?(?<prefixo>[a-z])-(?<codigo>[0-9a-z]{6}))",
            Pattern.CASE_INSENSITIVE);

    public ReferenciaCurta {
        Objects.requireNonNull(modulo, "modulo");
        Objects.requireNonNull(prefixo, "prefixo");
        Objects.requireNonNull(codigo, "codigo");
        if (!MODULO.matcher(modulo).matches() || !PREFIXO.matcher(prefixo).matches()
                || !CODIGO.matcher(codigo).matches()) {
            throw new IllegalArgumentException("Referência curta inválida: " + modulo + ":" + prefixo + "-" + codigo);
        }
    }

    public static ReferenciaCurta gerar(String modulo, String prefixo, RandomGenerator aleatorio) {
        StringBuilder codigo = new StringBuilder(TAMANHO);
        for (int i = 0; i < TAMANHO; i++) {
            codigo.append(ALFABETO.charAt(aleatorio.nextInt(ALFABETO.length())));
        }
        return new ReferenciaCurta(modulo, prefixo, codigo.toString());
    }

    /**
     * Interpreta o que o assistente colou. Ignora maiúsculas e corrige as trocas comuns ao ditar
     * ({@code O} vira {@code 0}; {@code I} e {@code L} viram {@code 1}). Sem módulo, vale o informado.
     * Vazio se o texto não tem forma de referência curta; erro {@code modulo_errado} se é de outro módulo.
     */
    public static Optional<ReferenciaCurta> ler(String texto, String moduloEsperado) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher m = TEXTO.matcher(texto.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        boolean url = m.group("codigoUrl") != null;
        String modulo = url ? m.group("moduloUrl") : m.group("modulo");
        String prefixo = (url ? m.group("prefixoUrl") : m.group("prefixo")).toLowerCase(Locale.ROOT);
        String codigo = normalizar(url ? m.group("codigoUrl") : m.group("codigo"));
        modulo = modulo == null ? moduloEsperado : modulo.toLowerCase(Locale.ROOT);
        if (!modulo.equals(moduloEsperado)) {
            throw ErroTipado.de(CodigoErro.MODULO_ERRADO,
                    "A referência " + texto.strip() + " é do módulo " + modulo + ", não de " + moduloEsperado + ".");
        }
        if (!CODIGO.matcher(codigo).matches()) {
            return Optional.empty();
        }
        return Optional.of(new ReferenciaCurta(modulo, prefixo, codigo));
    }

    private static String normalizar(String codigo) {
        return codigo.toUpperCase(Locale.ROOT).replace('O', '0').replace('I', '1').replace('L', '1');
    }

    /** {@code t-8K3QZ2} */
    public String id() {
        return prefixo + "-" + codigo;
    }

    /** {@code fin:t-8K3QZ2} */
    public String idCompleto() {
        return modulo + ":" + id();
    }

    /** {@code /fin/t/8K3QZ2}, a acrescentar à origem da interface web. */
    public String caminho() {
        return "/" + modulo + "/" + prefixo + "/" + codigo;
    }

    @Override
    public String toString() {
        return idCompleto();
    }
}
