package tools.alicerce.financeiro.adaptador.mcp;

import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.context.MetaProvider;

/** Diz ao ChatGPT que o parâmetro {@code arquivo} recebe um anexo da conversa. */
public final class MetaArquivo implements MetaProvider {

    public MetaArquivo() {}

    @Override
    public Map<String, Object> getMeta() {
        return Map.of("openai/fileParams", List.of("arquivo"));
    }
}
