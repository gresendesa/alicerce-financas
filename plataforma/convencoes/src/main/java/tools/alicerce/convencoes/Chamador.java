package tools.alicerce.convencoes;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Quem chama uma capacidade, já validado pela segurança. Da camada de capacidades para baixo o
 * código recebe isto, nunca um token.
 *
 * @param dono o dono dos dados
 * @param cliente o cliente OAuth que chamou ({@code azp}): qual assistente
 * @param escopos os escopos concedidos ao token
 * @param usuario o nome de login no provedor de identidade, para mostrar qual conta está conectada; nulo se o
 *     token não o traz
 */
public record Chamador(UUID dono, String cliente, Set<String> escopos, String usuario) {

    /** Chave do chamador no contexto de transporte do MCP. */
    public static final String CHAVE_CONTEXTO = "tools.alicerce.chamador";

    public Chamador {
        Objects.requireNonNull(dono, "dono");
        Objects.requireNonNull(cliente, "cliente");
        escopos = Set.copyOf(escopos);
    }

    public Chamador(UUID dono, String cliente, Set<String> escopos) {
        this(dono, cliente, escopos, null);
    }
}
