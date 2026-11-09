package tools.alicerce.financeiro.dominio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Os formatos aceitos como comprovante. O tipo vem dos bytes do arquivo, nunca do que o cliente declara. */
public enum TipoImagem {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    HEIC("image/heic");

    /** 10 MB. */
    public static final int TAMANHO_MAXIMO = 10 * 1024 * 1024;

    private static final byte[] ASSINATURA_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ASSINATURA_PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final Set<String> MARCAS_HEIC = Set.of("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs");
    private static final Set<String> MARCAS_GENERICAS = Set.of("mif1", "msf1");

    private final String midia;

    TipoImagem(String midia) {
        this.midia = midia;
    }

    public String midia() {
        return midia;
    }

    public static TipoImagem deMidia(String midia) {
        return Arrays.stream(values()).filter(t -> t.midia.equals(midia)).findFirst().orElseThrow();
    }

    public static Optional<TipoImagem> detectar(byte[] b) {
        if (comeca(b, ASSINATURA_JPEG)) {
            return Optional.of(JPEG);
        }
        if (comeca(b, ASSINATURA_PNG)) {
            return Optional.of(PNG);
        }
        if (b.length >= 12 && texto(b, 0).equals("RIFF") && texto(b, 8).equals("WEBP")) {
            return Optional.of(WEBP);
        }
        return heic(b) ? Optional.of(HEIC) : Optional.empty();
    }

    private static boolean heic(byte[] b) {
        if (b.length < 12 || !texto(b, 4).equals("ftyp")) {
            return false;
        }
        String principal = texto(b, 8);
        if (MARCAS_HEIC.contains(principal)) {
            return true;
        }
        if (!MARCAS_GENERICAS.contains(principal)) {
            return false;
        }
        // Marca genérica (também usada por AVIF): a lista de compatíveis decide.
        long caixa = ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
        int fim = (int) Math.min(caixa, b.length);
        for (int i = 16; i + 4 <= fim; i += 4) {
            if (MARCAS_HEIC.contains(texto(b, i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean comeca(byte[] b, byte[] assinatura) {
        return b.length >= assinatura.length && Arrays.equals(b, 0, assinatura.length, assinatura, 0, assinatura.length);
    }

    private static String texto(byte[] b, int inicio) {
        return new String(b, inicio, 4, StandardCharsets.ISO_8859_1);
    }
}
