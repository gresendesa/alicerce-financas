package tools.alicerce.convencoes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Um valor em dinheiro: {@code BigDecimal} com escala 2 na interface e no domínio, centavos em
 * {@code long} no banco. Nunca {@code double}. A escala é 2 para toda moeda; moedas com outro
 * número de casas (iene, dinar do Bahrein) não têm conta própria, só valor original.
 */
public record Dinheiro(BigDecimal valor, Currency moeda) implements Comparable<Dinheiro> {

    public static final int ESCALA = 2;

    /** Decimal com ponto e até duas casas, como o assistente envia: {@code "1234.56"}. */
    private static final Pattern FORMATO = Pattern.compile("-?\\d{1,15}(\\.\\d{1,2})?");

    public Dinheiro {
        Objects.requireNonNull(valor, "valor");
        Objects.requireNonNull(moeda, "moeda");
        if (valor.scale() > ESCALA && valor.stripTrailingZeros().scale() > ESCALA) {
            throw new IllegalArgumentException("Valor com mais de " + ESCALA + " casas decimais: " + valor);
        }
        valor = valor.setScale(ESCALA, RoundingMode.UNNECESSARY);
        // Garante que cabe em centavos no banco.
        valor.unscaledValue().longValueExact();
    }

    public static Dinheiro zero(Currency moeda) {
        return new Dinheiro(BigDecimal.ZERO, moeda);
    }

    public static Dinheiro deCentavos(long centavos, Currency moeda) {
        return new Dinheiro(BigDecimal.valueOf(centavos, ESCALA), moeda);
    }

    /**
     * Lê o valor como chega da interface. Recusa vírgula, separador de milhar, notação científica e
     * mais de duas casas, para o modelo nunca errar a escala sem perceber.
     */
    public static Dinheiro ler(String texto, Currency moeda, String campo) {
        if (texto == null || !FORMATO.matcher(texto).matches()) {
            throw ErroTipado.validacao(campo,
                    "Valor inválido: use um decimal com ponto e até duas casas, como \"1234.56\".");
        }
        return new Dinheiro(new BigDecimal(texto), moeda);
    }

    public long centavos() {
        return valor.unscaledValue().longValueExact();
    }

    public Dinheiro somar(Dinheiro outro) {
        return new Dinheiro(valor.add(daMesmaMoeda(outro).valor), moeda);
    }

    public Dinheiro subtrair(Dinheiro outro) {
        return new Dinheiro(valor.subtract(daMesmaMoeda(outro).valor), moeda);
    }

    public Dinheiro negar() {
        return new Dinheiro(valor.negate(), moeda);
    }

    public boolean ehZero() {
        return valor.signum() == 0;
    }

    public boolean ehNegativo() {
        return valor.signum() < 0;
    }

    /** O valor como sai na interface: {@code "1234.56"}, sem formatação. */
    public String texto() {
        return valor.toPlainString();
    }

    @Override
    public int compareTo(Dinheiro outro) {
        return valor.compareTo(daMesmaMoeda(outro).valor);
    }

    private Dinheiro daMesmaMoeda(Dinheiro outro) {
        if (!moeda.equals(outro.moeda)) {
            throw new IllegalArgumentException("Moedas diferentes: " + moeda + " e " + outro.moeda);
        }
        return outro;
    }

    @Override
    public String toString() {
        return moeda.getCurrencyCode() + " " + texto();
    }
}
