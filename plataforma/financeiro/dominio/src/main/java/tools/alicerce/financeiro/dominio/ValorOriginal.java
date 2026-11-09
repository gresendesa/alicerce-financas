package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;

/**
 * Como a compra aconteceu em outra moeda. Só descreve: o valor do lançamento continua na moeda da conta,
 * e é ele que entra em saldos e somas.
 *
 * <p>Regra de arredondamento: o valor na moeda da conta é {@code valor × cotacao} com {@link RoundingMode#HALF_EVEN}
 * na escala 2, e a cotação implícita é {@code valor na conta ÷ valor} com o mesmo modo na escala
 * {@value #ESCALA_COTACAO}. Quando os três são informados, a conta pode divergir em até um centavo: vale o valor
 * informado, que é o do extrato.
 *
 * @param valor o valor na moeda original, na escala do expoente ISO 4217 dela
 * @param cotacao quanto vale uma unidade da moeda original, na moeda da conta
 * @param dataCotacao a data da cotação
 */
public record ValorOriginal(BigDecimal valor, Currency moeda, BigDecimal cotacao, LocalDate dataCotacao) {

    public static final int ESCALA_COTACAO = 10;

    private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");
    private static final Pattern VALOR = Pattern.compile("\\d{1,14}(\\.\\d{1,4})?");
    private static final Pattern COTACAO = Pattern.compile("\\d{1,10}(\\.\\d{1,10})?");

    /** O que uma conversão devolve: o valor na moeda da conta e a descrição da compra original. */
    public record Conversao(Dinheiro valor, ValorOriginal original) {}

    public ValorOriginal {
        Objects.requireNonNull(valor, "valor");
        Objects.requireNonNull(moeda, "moeda");
        Objects.requireNonNull(cotacao, "cotacao");
        Objects.requireNonNull(dataCotacao, "dataCotacao");
        valor = valor.setScale(moeda.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        cotacao = cotacao.setScale(ESCALA_COTACAO, RoundingMode.UNNECESSARY);
    }

    /** Lê o código ISO 4217. Recusa o que não existe, o que não é dinheiro (XAU, XXX) e a própria moeda da conta. */
    public static Currency lerMoeda(String codigo, Currency moedaDaConta, String campo) {
        Currency moeda = lerCodigo(codigo, campo);
        if (moeda.equals(moedaDaConta)) {
            throw ErroTipado.validacao(campo, moeda.getCurrencyCode() + " já é a moeda das contas: informe só o valor, sem moeda original.");
        }
        return moeda;
    }

    /** A moeda de uma conta. Só expoente 2, como USD e EUR: dinheiro no domínio tem sempre escala 2. */
    public static Currency lerMoedaDeConta(String codigo, String campo) {
        Currency moeda = lerCodigo(codigo, campo);
        if (moeda.getDefaultFractionDigits() != Dinheiro.ESCALA) {
            throw ErroTipado.validacao(campo, moeda.getCurrencyCode() + " tem " + moeda.getDefaultFractionDigits()
                    + " casas decimais, e uma conta só aceita moeda de duas casas, como USD ou EUR.");
        }
        return moeda;
    }

    private static Currency lerCodigo(String codigo, String campo) {
        String normalizado = codigo.strip().toUpperCase(Locale.ROOT);
        Currency moeda;
        try {
            moeda = Currency.getInstance(normalizado);
        } catch (IllegalArgumentException inexistente) {
            throw ErroTipado.validacao(campo, "Moeda '" + codigo.strip() + "' não existe na ISO 4217: use um código como USD ou EUR.");
        }
        if (moeda.getDefaultFractionDigits() < 0) {
            throw ErroTipado.validacao(campo, "'" + normalizado + "' não é uma moeda de pagamento: use um código como USD ou EUR.");
        }
        return moeda;
    }

    /** Lê o valor original, com no máximo as casas que a moeda tem. */
    public static BigDecimal lerValor(String texto, Currency moeda, String campo) {
        String limpo = texto.strip();
        int casas = moeda.getDefaultFractionDigits();
        if (!VALOR.matcher(limpo).matches() || new BigDecimal(limpo).scale() > casas) {
            throw ErroTipado.validacao(campo, "Valor original inválido: use um decimal com ponto e até " + casas + " casas em "
                    + moeda.getCurrencyCode() + ".");
        }
        BigDecimal valor = new BigDecimal(limpo);
        if (valor.signum() <= 0) {
            throw ErroTipado.validacao(campo, "O valor tem de ser maior que zero.");
        }
        return valor;
    }

    public static BigDecimal lerCotacao(String texto, String campo) {
        String limpo = texto.strip();
        if (!COTACAO.matcher(limpo).matches() || new BigDecimal(limpo).signum() <= 0) {
            throw ErroTipado.validacao(campo, "Cotação inválida: use um decimal maior que zero, com ponto e até "
                    + ESCALA_COTACAO + " casas, como \"5.52\".");
        }
        return new BigDecimal(limpo);
    }

    /**
     * Calcula o que falta e confere os três valores quando todos vêm. Pelo menos {@code informado} ou
     * {@code cotacao} tem de vir.
     *
     * @param informado o valor na moeda da conta, ou {@code null} para calcular
     * @param cotacao a cotação, ou {@code null} para usar a implícita
     * @param prefixo o que antecede o nome dos campos nos erros
     */
    public static Conversao converter(BigDecimal valor, Currency moeda, Dinheiro informado, BigDecimal cotacao,
            LocalDate dataCotacao, Currency moedaDaConta, String prefixo) {
        if (informado == null && cotacao == null) {
            throw new IllegalArgumentException("Informe o valor na moeda da conta ou a cotação");
        }
        if (cotacao == null) {
            BigDecimal implicita = informado.valor().divide(valor, ESCALA_COTACAO, RoundingMode.HALF_EVEN);
            if (implicita.signum() == 0) {
                throw ErroTipado.validacao(prefixo + "valor", "O valor é pequeno demais diante do valor original para dar uma cotação.");
            }
            return new Conversao(informado, new ValorOriginal(valor, moeda, implicita, dataCotacao));
        }
        Dinheiro calculado = calcular(valor, cotacao, moedaDaConta, prefixo);
        ValorOriginal original = new ValorOriginal(valor, moeda, cotacao, dataCotacao);
        if (informado == null) {
            return new Conversao(calculado, original);
        }
        BigDecimal diferenca = calculado.valor().subtract(informado.valor()).abs();
        if (diferenca.compareTo(TOLERANCIA) > 0) {
            throw ErroTipado.validacao(prefixo + "cotacao", "Valores incoerentes: " + valor.toPlainString() + " " + moeda.getCurrencyCode()
                    + " a " + cotacao.toPlainString() + " dá " + calculado.texto() + ", e o valor informado é " + informado.texto()
                    + ". Confira com o dono; se o extrato trouxer o valor em " + moedaDaConta.getCurrencyCode()
                    + ", envie-o sem a cotação.");
        }
        return new Conversao(informado, original);
    }

    /**
     * O caminho inverso, num câmbio: parte do valor na moeda do dono e da cotação e calcula o lado estrangeiro,
     * com {@link RoundingMode#HALF_EVEN} na escala 2. A moeda tem expoente 2, porque é a de uma conta.
     */
    public static Conversao daMoedaDoDono(Dinheiro valor, Currency moeda, BigDecimal cotacao, LocalDate dataCotacao, String prefixo) {
        BigDecimal original = valor.valor().divide(cotacao, moeda.getDefaultFractionDigits(), RoundingMode.HALF_EVEN);
        if (original.signum() == 0) {
            throw ErroTipado.validacao(prefixo + "cotacao", "O valor convertido dá zero: confira o valor e a cotação.");
        }
        return new Conversao(valor, new ValorOriginal(original, moeda, cotacao, dataCotacao));
    }

    private static Dinheiro calcular(BigDecimal valor, BigDecimal cotacao, Currency moedaDaConta, String prefixo) {
        BigDecimal resultado = valor.multiply(cotacao, MathContext.UNLIMITED).setScale(Dinheiro.ESCALA, RoundingMode.HALF_EVEN);
        if (resultado.signum() == 0) {
            throw ErroTipado.validacao(prefixo + "cotacao", "O valor convertido dá zero: confira o valor original e a cotação.");
        }
        try {
            return new Dinheiro(resultado, moedaDaConta);
        } catch (ArithmeticException grandeDemais) {
            throw ErroTipado.validacao(prefixo + "cotacao", "O valor convertido é grande demais: confira o valor original e a cotação.");
        }
    }

    /** O valor nas unidades mínimas da moeda, como vai para o banco. */
    public long unidadesMinimas() {
        return valor.unscaledValue().longValueExact();
    }

    public static ValorOriginal deUnidadesMinimas(long unidades, Currency moeda, BigDecimal cotacao, LocalDate dataCotacao) {
        return new ValorOriginal(BigDecimal.valueOf(unidades, moeda.getDefaultFractionDigits()), moeda, cotacao, dataCotacao);
    }

    public ValorOriginal comDataCotacao(LocalDate data) {
        return new ValorOriginal(valor, moeda, cotacao, data);
    }

    /** A cotação como sai na interface, sem os zeros à direita: {@code "5.52"}. */
    public String cotacaoTexto() {
        return cotacao.stripTrailingZeros().toPlainString();
    }
}
