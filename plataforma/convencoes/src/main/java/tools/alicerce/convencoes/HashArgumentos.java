package tools.alicerce.convencoes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hash SHA-256 dos argumentos de uma escrita, para a idempotência distinguir a repetição da mesma
 * chamada de uma chamada diferente com a mesma chave. Calculado sobre a forma canônica: chaves de
 * mapa em ordem, campos nulos omitidos (ausente e nulo são o mesmo pedido) e números comparados
 * pelo valor ({@code 45.0} e {@code 45.00} são iguais). Recebe os argumentos já normalizados, sem
 * a própria chave de idempotência.
 */
public final class HashArgumentos {

    private HashArgumentos() {}

    public static String de(Map<String, ?> argumentos) {
        StringBuilder canonico = new StringBuilder();
        escrever(argumentos, canonico);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonico.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 é obrigatório em toda JVM", e);
        }
    }

    /** A forma canônica, exposta para os testes e para depuração. */
    static String canonico(Map<String, ?> argumentos) {
        StringBuilder canonico = new StringBuilder();
        escrever(argumentos, canonico);
        return canonico.toString();
    }

    private static void escrever(Object valor, StringBuilder saida) {
        switch (valor) {
            case null -> saida.append("null");
            case String s -> texto(s, saida);
            case Boolean b -> saida.append(b);
            case Integer i -> saida.append(i);
            case Long l -> saida.append(l);
            case Short s -> saida.append(s);
            case BigInteger i -> saida.append(i);
            case BigDecimal d -> saida.append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString());
            case Dinheiro d -> {
                texto(d.moeda().getCurrencyCode(), saida);
                saida.append(':').append(d.texto());
            }
            case Enum<?> e -> texto(e.name(), saida);
            case UUID u -> texto(u.toString(), saida);
            case Currency c -> texto(c.getCurrencyCode(), saida);
            case TemporalAccessor t -> texto(t.toString(), saida);
            case Map<?, ?> m -> mapa(m, saida);
            case List<?> l -> {
                saida.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) {
                        saida.append(',');
                    }
                    escrever(l.get(i), saida);
                }
                saida.append(']');
            }
            default -> throw new IllegalArgumentException(
                    "Tipo sem forma canônica nos argumentos: " + valor.getClass().getName());
        }
    }

    private static void mapa(Map<?, ?> mapa, StringBuilder saida) {
        List<String> chaves = new ArrayList<>();
        for (Map.Entry<?, ?> e : mapa.entrySet()) {
            if (!(e.getKey() instanceof String chave)) {
                throw new IllegalArgumentException("Chave de mapa não textual nos argumentos: " + e.getKey());
            }
            if (e.getValue() != null) {
                chaves.add(chave);
            }
        }
        chaves.sort(null);
        saida.append('{');
        for (int i = 0; i < chaves.size(); i++) {
            if (i > 0) {
                saida.append(',');
            }
            texto(chaves.get(i), saida);
            saida.append(':');
            escrever(mapa.get(chaves.get(i)), saida);
        }
        saida.append('}');
    }

    private static void texto(String s, StringBuilder saida) {
        saida.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> saida.append("\\\"");
                case '\\' -> saida.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        saida.append(String.format("\\u%04x", (int) c));
                    } else {
                        saida.append(c);
                    }
                }
            }
        }
        saida.append('"');
    }
}
