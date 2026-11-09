package tools.alicerce.financeiro.dominio;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/** O dono dos dados, identificado no Keycloak por emissor + {@code sub}. */
public record Dono(UUID id, ZoneId fuso, Currency moeda) {

    public Dono {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fuso, "fuso");
        Objects.requireNonNull(moeda, "moeda");
    }
}
