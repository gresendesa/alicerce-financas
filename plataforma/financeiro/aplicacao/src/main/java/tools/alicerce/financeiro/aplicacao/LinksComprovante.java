package tools.alicerce.financeiro.aplicacao;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * O link temporário que abre um comprovante. Como o token do {@code desfazer}, não vive no banco:
 * {@code resolver_referencia} é só leitura. Reiniciar a aplicação invalida os links em aberto.
 */
@Component
public class LinksComprovante {

    /** O que um link assinado e dentro da validade aponta. */
    public record Alvo(UUID dono, UUID comprovante) {}

    public static final Duration VALIDADE = Duration.ofMinutes(15);

    private static final String ALGORITMO = "HmacSHA256";
    private static final Base64.Encoder CODIFICADOR = Base64.getUrlEncoder().withoutPadding();

    private final Clock relogio;
    private final SecretKeySpec chave;

    @Autowired
    LinksComprovante() {
        this(Clock.systemUTC());
    }

    public LinksComprovante(Clock relogio) {
        this.relogio = relogio;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.chave = new SecretKeySpec(bytes, ALGORITMO);
    }

    public Instant validoAte() {
        return relogio.instant().plus(VALIDADE);
    }

    public String emitir(UUID dono, UUID comprovante) {
        String corpo = dono + "|" + comprovante + "|" + validoAte().getEpochSecond();
        return CODIFICADOR.encodeToString(corpo.getBytes(StandardCharsets.UTF_8)) + "." + assinatura(corpo);
    }

    /** Vazio se o link foi adulterado ou venceu. */
    public Optional<Alvo> abrir(String token) {
        String[] partes = token.split("\\.");
        if (partes.length != 2) {
            return Optional.empty();
        }
        String corpo;
        try {
            corpo = new String(Base64.getUrlDecoder().decode(partes[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalido) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(assinatura(corpo).getBytes(StandardCharsets.UTF_8), partes[1].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        String[] campos = corpo.split("\\|");
        if (relogio.instant().isAfter(Instant.ofEpochSecond(Long.parseLong(campos[2])))) {
            return Optional.empty();
        }
        return Optional.of(new Alvo(UUID.fromString(campos[0]), UUID.fromString(campos[1])));
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
