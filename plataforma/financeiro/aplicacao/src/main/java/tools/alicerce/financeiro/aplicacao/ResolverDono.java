package tools.alicerce.financeiro.aplicacao;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.seguranca.ResolvedorDono;

/**
 * Cria o dono no primeiro acesso. A segurança só chega aqui depois de conferir o papel de dono.
 * É identidade, não capacidade: não passa pelo registrador de operações.
 */
@Component
class ResolverDono implements ResolvedorDono {

    private final RepositorioDono donos;

    ResolverDono(RepositorioDono donos) {
        this.donos = donos;
    }

    @Override
    public UUID resolver(String emissor, String sujeito) {
        return donos.garantir(emissor, sujeito);
    }
}
