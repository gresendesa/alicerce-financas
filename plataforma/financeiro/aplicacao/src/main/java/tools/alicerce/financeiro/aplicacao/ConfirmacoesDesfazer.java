package tools.alicerce.financeiro.aplicacao;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;

/**
 * O token de confirmação do {@code desfazer}. Não vive no banco: é a impressão do que a prévia mostrou,
 * o dono e a validade, assinados com uma chave sorteada na subida. Assim a prévia continua sem gravar
 * nada. Reiniciar a aplicação invalida os tokens em aberto, e o assistente só pede outra prévia.
 */
@Component
public class ConfirmacoesDesfazer {

    public static final Duration VALIDADE = Duration.ofMinutes(10);

    private static final String ALGORITMO = "HmacSHA256";
    private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();

    private final Clock relogio;
    private final SecretKeySpec chave;

    @Autowired
    ConfirmacoesDesfazer() {
        this(Clock.systemUTC());
    }

    public ConfirmacoesDesfazer(Clock relogio) {
        this.relogio = relogio;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.chave = new SecretKeySpec(bytes, ALGORITMO);
    }

    public Instant validoAte() {
        return relogio.instant().plus(VALIDADE);
    }

    public String emitir(UUID dono, String impressao) {
        String corpo = dono + "|" + validoAte().getEpochSecond() + "|" + impressao;
        return CODIFICADOR.encodeToString(corpo.getBytes(StandardCharsets.UTF_8)) + "." + assinatura(corpo);
    }

    /** Confere assinatura, dono e validade, e devolve a impressão que a prévia mostrou. */
    public String conferir(UUID dono, String token) {
        String[] partes = token.strip().split("\\.");
        String corpo;
        try {
            corpo = partes.length == 2 ? new String(Base64.getUrlDecoder().decode(partes[0]), StandardCharsets.UTF_8) : "";
        } catch (IllegalArgumentException invalido) {
            throw invalida();
        }
        boolean assinado = partes.length == 2 && MessageDigest.isEqual(
                assinatura(corpo).getBytes(StandardCharsets.UTF_8), partes[1].getBytes(StandardCharsets.UTF_8));
        String[] campos = corpo.split("\\|");
        if (!assinado || campos.length != 3 || !campos[0].equals(dono.toString())) {
            throw invalida();
        }
        if (relogio.instant().isAfter(Instant.ofEpochSecond(Long.parseLong(campos[1])))) {
            throw ErroTipado.de(CodigoErro.CONFIRMACAO_EXPIRADA, "confirmacao",
                    "A confirmação venceu. Peça a prévia de novo e mostre ao dono.");
        }
        return campos[2];
    }

    /** O resumo do que vai ser revertido, para saber se o alvo mudou desde a prévia. */
    public static String impressao(List<String> linhas) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(String.join("\n", linhas).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException impossivel) {
            throw new IllegalStateException(impossivel);
        }
    }

    public static ErroTipado invalida() {
        return ErroTipado.de(CodigoErro.CONFIRMACAO_INVALIDA, "confirmacao",
                "A confirmação não vale mais: o alvo mudou desde a prévia, ou o token não é este. Peça a prévia de novo.");
    }

    private String assinatura(String corpo) {
        try {
            Mac mac = Mac.getInstance(ALGORITMO);
            mac.init(chave);
            return CODIFICADOR.encodeToString(mac.doFinal(corpo.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossivel) {
            throw new IllegalStateException(impossivel);
        }
    }
}
