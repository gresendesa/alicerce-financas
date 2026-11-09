package tools.alicerce.financeiro.dominio;

import java.util.Objects;
import java.util.UUID;

/**
 * A marca de uma transferência numa meta.
 *
 * @param resgate o dinheiro saiu da meta; falso, entrou nela
 */
public record AporteMeta(UUID meta, boolean resgate) {

    public AporteMeta {
        Objects.requireNonNull(meta, "meta");
    }
}
