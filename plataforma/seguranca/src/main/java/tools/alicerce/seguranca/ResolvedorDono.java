package tools.alicerce.seguranca;

import java.util.UUID;

/** Checagem 6: resolve (e cria no primeiro acesso) o dono identificado por emissor + {@code sub}. */
public interface ResolvedorDono {

    UUID resolver(String emissor, String sujeito);
}
