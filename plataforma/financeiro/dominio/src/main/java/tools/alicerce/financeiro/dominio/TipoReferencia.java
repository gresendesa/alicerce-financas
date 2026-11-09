package tools.alicerce.financeiro.dominio;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;
import tools.alicerce.convencoes.ReferenciaCurta;

/** Os tipos de entidade do financeiro que têm endereço curto, com o prefixo de cada um. */
public enum TipoReferencia {
    LANCAMENTO("t"),
    CONTA("a"),
    FATURA("f"),
    COMPROMISSO("k"),
    META("m"),
    COMPROVANTE("n"),
    RETRATO("s"),
    OPERACAO("o");

    /** O módulo nos ids completos ({@code fin:t-8K3QZ2}) e nas URLs ({@code /fin/t/8K3QZ2}). */
    public static final String MODULO = "fin";

    private final String prefixo;

    TipoReferencia(String prefixo) {
        this.prefixo = prefixo;
    }

    public String prefixo() {
        return prefixo;
    }

    /** O tipo do prefixo ({@code t}, {@code a}...); vazio se o prefixo não é do financeiro. */
    public static Optional<TipoReferencia> doPrefixo(String prefixo) {
        return Arrays.stream(values()).filter(t -> t.prefixo.equals(prefixo)).findFirst();
    }

    /** O nome gravado em {@code referencia_curta.tipo_entidade}. */
    public String entidade() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ReferenciaCurta gerar(RandomGenerator aleatorio) {
        return ReferenciaCurta.gerar(MODULO, prefixo, aleatorio);
    }
}
