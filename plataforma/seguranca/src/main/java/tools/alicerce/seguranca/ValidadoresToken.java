package tools.alicerce.seguranca;

import java.time.Duration;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/** Checagens 2 a 5 da seção Autorização: emissor, audiência, validade e cliente. */
final class ValidadoresToken {

    static final Duration TOLERANCIA_RELOGIO = Duration.ofSeconds(30);

    private ValidadoresToken() {}

    static OAuth2TokenValidator<Jwt> criar(PropriedadesSeguranca propriedades) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtIssuerValidator(propriedades.emissor()),
                audiencia(propriedades.recurso()),
                new JwtTimestampValidator(TOLERANCIA_RELOGIO),
                cliente(propriedades.clientes())));
    }

    private static OAuth2TokenValidator<Jwt> audiencia(String recurso) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(recurso)
                ? OAuth2TokenValidatorResult.success()
                : falha("O token não foi emitido para " + recurso);
    }

    private static OAuth2TokenValidator<Jwt> cliente(List<String> clientes) {
        return jwt -> {
            String azp = jwt.getClaimAsString("azp");
            return azp != null && clientes.contains(azp)
                    ? OAuth2TokenValidatorResult.success()
                    : falha("Cliente desconhecido: " + azp);
        };
    }

    private static OAuth2TokenValidatorResult falha(String descricao) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, descricao, null));
    }
}
