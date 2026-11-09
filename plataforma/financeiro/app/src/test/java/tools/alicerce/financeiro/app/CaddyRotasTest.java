package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** O Caddyfile versionado, com uma aplicação falsa atrás: o que cada host serve e o que não. */
class CaddyRotasTest {

    private static final String IMAGEM = System.getProperty("alicerce.imagem.caddy");
    private static final Network REDE = Network.newNetwork();

    private static final GenericContainer<?> APLICACAO = new GenericContainer<>(IMAGEM)
            .withNetwork(REDE)
            .withNetworkAliases("financeiro")
            .withCommand("caddy", "respond", "--listen", ":8080", "--body", "da-aplicacao");
    private static final GenericContainer<?> CADDY = new GenericContainer<>(IMAGEM)
            .withNetwork(REDE)
            // Cópia, não bind mount: o build roda num contêiner e o daemon não enxerga o caminho dele.
            .withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("alicerce.caddyfile")), "/etc/caddy/Caddyfile")
            .waitingFor(Wait.forLogMessage(".*serving initial configuration.*", 1));

    @BeforeAll
    static void subir() {
        APLICACAO.start();
        CADDY.start();
    }

    @AfterAll
    static void descer() {
        CADDY.stop();
        APLICACAO.stop();
    }

    @Test
    void oHostDaInterfaceServeAsRotasDaInterface() throws Exception {
        for (String caminho : List.of("/", "/callback", "/fin/contas", "/fin/lancamentos", "/fin/operacoes", "/fin/relatorios?de=2026-10-01", "/fin/t/8K3QZ2", "/fin/a/8K3QZ2",
                "/fin/f/8K3QZ2", "/fin/o/8K3QZ2", "/fin/s/8K3QZ2", "/fin/n/8K3QZ2", "/web/app.js", "/sw.js", "/manifest.webmanifest",
                "/api/fin/vista?caminho=/fin/contas", "/api/fin/lancamentos/8K3QZ2/anexo", "/fin/anexo/abc", "/fin/comprovante/abc", "/fin/p/abc",
                "/fin/p/abc/vista", "/fin/p/abc/n/8K3QZ2")) {
            String resposta = pedir("app.alicerce.tools", caminho);

            assertThat(resposta).as(caminho).contains("200 OK", "da-aplicacao");
            assertThat(resposta).as(caminho).containsIgnoringCase("X-Robots-Tag: noindex");
        }
    }

    @Test
    void oHostDaInterfaceNaoExpoeOProtocoloNemOsMetadados() throws Exception {
        for (String caminho : List.of("/mcp", "/.well-known/oauth-protected-resource/mcp", "/actuator/health", "/actuator")) {
            String resposta = pedir("app.alicerce.tools", caminho);

            assertThat(resposta).as(caminho).contains("404").doesNotContain("da-aplicacao");
            assertThat(resposta).as(caminho).containsIgnoringCase("X-Robots-Tag: noindex");
        }
    }

    @Test
    void oHostDoProtocoloSegueIgualEOutrosNaoSaoServidos() throws Exception {
        assertThat(pedir("fin.alicerce.tools", "/mcp")).contains("200 OK", "da-aplicacao");
        assertThat(pedir("outro.example", "/")).contains("404").doesNotContain("da-aplicacao");
    }

    private static String pedir(String host, String caminho) throws Exception {
        // HTTP cru: o wget da imagem não mostra os cabeçalhos de uma resposta de erro.
        String pedido = "GET " + caminho + " HTTP/1.1\\r\\nHost: " + host + "\\r\\nConnection: close\\r\\n\\r\\n";
        ExecResult resultado = CADDY.execInContainer("sh", "-c", "(printf '" + pedido + "'; sleep 0.3) | nc 127.0.0.1 80");
        return resultado.getStdout() + resultado.getStderr();
    }
}
