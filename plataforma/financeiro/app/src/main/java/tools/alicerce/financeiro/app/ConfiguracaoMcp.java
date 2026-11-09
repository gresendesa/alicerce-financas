package tools.alicerce.financeiro.app;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.seguranca.PropriedadesSeguranca;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transporte Streamable HTTP sem estado, substituindo o da autoconfiguração para levar o
 * {@link Chamador} até as tools e validar o cabeçalho {@code Origin} (proteção contra DNS rebinding).
 */
@Configuration(proxyBeanMethods = false)
class ConfiguracaoMcp {

    /** Anuncia a extensão MCP Apps, que é negociada; o resto vem das propriedades. */
    @Bean
    McpSchema.ServerCapabilities.Builder capabilitiesBuilder() {
        return McpSchema.ServerCapabilities.builder()
                .extensions(Map.of("io.modelcontextprotocol/ui",
                        Map.of("mimeTypes", List.of("text/html;profile=mcp-app"))));
    }

    @Bean
    WebMvcStatelessServerTransport webMvcStatelessServerTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper json, McpServerStreamableHttpProperties mcp,
            PropriedadesSeguranca seguranca) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint(mcp.getMcpEndpoint())
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigins(seguranca.origensPermitidas())
                        .build())
                .contextExtractor(requisicao -> requisicao.servletRequest().getAttribute(Chamador.CHAVE_CONTEXTO)
                        instanceof Chamador chamador
                                ? McpTransportContext.create(Map.of(Chamador.CHAVE_CONTEXTO, chamador))
                                : McpTransportContext.EMPTY)
                .build();
    }
}
