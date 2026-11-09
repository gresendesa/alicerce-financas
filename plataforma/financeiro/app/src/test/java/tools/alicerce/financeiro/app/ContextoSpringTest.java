package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/** Sobe o contexto Spring inteiro: a autoconfiguração só falha em tempo de execução. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class ContextoSpringTest {

    @Autowired
    ApplicationContext contexto;

    @Test
    void sobeOContextoInteiro() {
        assertThat(contexto.getBeanNamesForType(McpStatelessSyncServer.class))
                .as("servidor MCP sem estado registrado")
                .hasSize(1);
    }
}
