package tools.alicerce.financeiro.aplicacao;

import java.util.Optional;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.Dono;

/** Porta de persistência dos donos. */
public interface RepositorioDono {

    /** O id do dono com esta identidade, criado agora se ainda não existir. */
    UUID garantir(String emissor, String sujeito);

    Optional<Dono> buscar(UUID id);
}
