package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import tools.alicerce.convencoes.Chamador;

/** Lê o {@link Chamador} que a segurança deixou no contexto de transporte. */
final class Chamadores {

    private Chamadores() {}

    static Chamador de(McpTransportContext contexto) {
        if (contexto != null && contexto.get(Chamador.CHAVE_CONTEXTO) instanceof Chamador chamador) {
            return chamador;
        }
        throw new IllegalStateException("Chamada MCP sem chamador autenticado");
    }
}
