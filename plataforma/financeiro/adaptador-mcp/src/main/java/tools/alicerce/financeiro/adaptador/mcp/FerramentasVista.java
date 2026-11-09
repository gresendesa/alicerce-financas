package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.MetaProvider;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.Vistas;
import tools.alicerce.seguranca.EscopoExigido;

/** A vista dentro do assistente (MCP Apps): HTML fixo da plataforma, o mesmo renderizador da PWA. */
@Component
class FerramentasVista {

    static final String RECURSO = "ui://alicerce/vista";
    static final String TIPO = "text/html;profile=mcp-app";

    /** O vínculo entre a tool e a página, no campo padrão do MCP Apps. */
    public static final class MetaFerramenta implements MetaProvider {
        @Override
        public Map<String, Object> getMeta() {
            return Map.of("ui", Map.of("resourceUri", RECURSO));
        }
    }

    /** Sem domínios declarados: a política do hospedeiro bloqueia toda conexão externa. */
    public static final class MetaRecurso implements MetaProvider {
        @Override
        public Map<String, Object> getMeta() {
            return Map.of("ui", Map.of("csp", Map.of(), "prefersBorder", true));
        }
    }

    private final Vistas vistas;
    private final String pagina;

    FerramentasVista(Vistas vistas) {
        this.vistas = vistas;
        this.pagina = ler("/interface/vista.html")
                .replace("/*ESTILO*/", ler("/static/web/estilo.css"))
                .replace("/*RENDERIZADOR*/", ler("/static/web/renderizador.js"))
                .replace("/*MCP_APP*/", ler("/interface/mcp-app.js"));
    }

    @McpTool(
            name = "mostrar_vista",
            title = "Mostrar vista",
            description = """
                    Mostra uma tela dentro da conversa, a partir de dados do domínio. Com endereco, abre a tela padrão de uma URL \
                    devolvida pelas capacidades. Para compor, passe fontes, raiz e componentes. Fontes: lista de objetos com id, capacidade \
                    (obter_contexto, listar_lancamentos, obter_fatura, resolver_referencia, resumo_periodo ou listar_operacoes) e os \
                    argumentos dela; o resultado de cada uma fica em /id-da-fonte. Componentes: lista plana com id e componente, do \
                    catálogo pagina (titulo, filhos), cartao (titulo, filhos), texto (valor, estilo), totais (rotulo, itens com rotulo, valor e \
                    formato), lista (rotulo, origem, campo_titulo, campo_detalhe, campo_url), tabela (legenda, origem, colunas com titulo, campo, \
                    campo_url e formato, e campo_destaque, o campo verdadeiro na linha a destacar), grafico (rotulo, origem, campo_rotulo, campo_valor, formato), link_curto (rotulo, referencia, url) \
                    e link (rotulo, url). A raiz é uma pagina. Todo número entra só por {"caminho": "/fonte/campo"}: texto escrito com dígito, \
                    HTML ou componente fora do catálogo é recusado. Formato é texto, dinheiro, data ou numero (decimal, como uma cotação). Não repita na conversa os números da \
                    tela: quem calcula é o sistema.""",
            metaProvider = MetaFerramenta.class,
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult mostrarVista(McpTransportContext contexto,
            @McpToolParam(description = "URL ou caminho de uma tela, como https://app.alicerce.tools/fin/t/8K3QZ2.", required = false) String endereco,
            @McpToolParam(description = "Id do componente pagina em que a vista começa.", required = false) String raiz,
            @McpToolParam(description = "Os componentes da vista, em lista plana.", required = false) List<Map<String, Object>> componentes,
            @McpToolParam(description = "De onde vêm os dados.", required = false) List<Map<String, Object>> fontes) {
        return Respostas.vista(() -> vistas.compor(Chamadores.de(contexto), new Vistas.Composicao(endereco, raiz, componentes, fontes)));
    }

    @McpResource(
            uri = RECURSO,
            name = "vista",
            title = "Vista do Alicerce",
            description = "Página fixa que desenha uma vista validada pelo servidor.",
            mimeType = TIPO,
            metaProvider = MetaRecurso.class)
    public String pagina() {
        return pagina;
    }

    private static String ler(String recurso) {
        try (InputStream entrada = FerramentasVista.class.getResourceAsStream(recurso)) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
