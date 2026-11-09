package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.alicerce.seguranca.FiltroSemIndexacao;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Testes de aceite: as sete checagens de token, RFC 9728 e {@code obter_contexto}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class})
class AutorizacaoMcpTest {

    private static final String METADADOS = "https://fin.alicerce.tools/.well-known/oauth-protected-resource/mcp";
    private static final String OBTER_CONTEXTO = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"obter_contexto","arguments":{}}}""";
    private static final String LISTAR_TOOLS = """
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int porta;

    @Autowired
    DSLContext banco;

    @Test
    void semTokenResponde401ComMetadadosDoRecurso() throws Exception {
        HttpResponse<String> resposta = mcp(null, OBTER_CONTEXTO, null);

        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(desafio -> assertThat(desafio)
                .startsWith("Bearer ")
                .contains("resource_metadata=\"" + METADADOS + "\"")
                .contains("scope=\"fin:read fin:write fin:structure fin:profile fin:share\""));
        assertSemIndexacao(resposta);
    }

    @Test
    void metadadosDoRecursoApontamParaOKeycloak() throws Exception {
        HttpResponse<String> resposta = get("/.well-known/oauth-protected-resource/mcp");

        assertThat(resposta.statusCode()).isEqualTo(200);
        JsonNode metadados = json.readTree(resposta.body());
        assertThat(metadados.get("resource").asString()).isEqualTo("https://fin.alicerce.tools/mcp");
        assertThat(metadados.get("authorization_servers").get(0).asString()).isEqualTo(KeycloakDeTeste.emissor());
        assertThat(metadados.get("scopes_supported").toString()).contains("fin:read", "fin:write", "fin:share").doesNotContain("fin:undo", "fin:attach");
        assertThat(metadados.get("tls_client_certificate_bound_access_tokens").asBoolean()).isFalse();
        assertSemIndexacao(resposta);
    }

    @Test
    void tokenComAudienciaErradaResponde401() throws Exception {
        // Sem nenhum escopo fin:*, o mapper de audiência não põe o recurso no token.
        String token = Tokens.doGui("openid");

        HttpResponse<String> resposta = mcp(token, OBTER_CONTEXTO, null);

        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("error=\"invalid_token\"");
    }

    @Test
    void tokenComAssinaturaAdulteradaResponde401() throws Exception {
        String token = Tokens.doGui("fin:read");
        String adulterado = token.substring(0, token.lastIndexOf('.') + 1)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[256]);

        assertThat(mcp(adulterado, OBTER_CONTEXTO, null).statusCode()).isEqualTo(401);
    }

    @Test
    void clienteDesconhecidoResponde401() throws Exception {
        String token = Tokens.obter("desconhecido", KeycloakDeTeste.SEGREDO_DESCONHECIDO, "gui", "fin:read");

        HttpResponse<String> resposta = mcp(token, OBTER_CONTEXTO, null);

        assertThat(resposta.statusCode()).isEqualTo(401);
        assertThat(resposta.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("desconhecido");
    }

    @Test
    void usuarioSemPapelDeDonoResponde403EDonoNaoECriado() throws Exception {
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "intruso", "fin:read");

        HttpResponse<String> resposta = mcp(token, OBTER_CONTEXTO, null);

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.body()).contains("access_denied", "fin-owner");
        assertThat(banco.fetchCount(DONO, DONO.SUJEITO_OIDC.eq(sujeito(token)))).isZero();
    }

    @Test
    void tokenSemOEscopoDaToolResponde403InsufficientScope() throws Exception {
        String token = Tokens.doGui("fin:write");

        HttpResponse<String> resposta = mcp(token, OBTER_CONTEXTO, null);

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(desafio -> assertThat(desafio)
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"fin:read\""));
        assertThat(resposta.body()).contains("\"scope\":\"fin:read\"");
        assertSemIndexacao(resposta);

        // A recusa fica na trilha de auditoria do dono do token, sem os argumentos.
        var evento = banco.fetchOne("""
                select e.capacidade, e.cliente, e.codigo_erro, e.argumentos::text from evento_auditoria e join dono d on d.id = e.dono_id
                where d.sujeito_oidc = ? and e.codigo_erro = 'insufficient_scope' order by e.id desc limit 1""", sujeito(token));
        assertThat(evento).isNotNull();
        assertThat(evento.get(0, String.class)).isEqualTo("obter_contexto");
        assertThat(evento.get(1, String.class)).isEqualTo("claude-ai");
        assertThat(evento.get(3, String.class)).isEqualTo("{}");
    }

    @Test
    void obterContextoCriaODonoNoPrimeiroAcesso() throws Exception {
        String token = Tokens.doGui("fin:read");

        HttpResponse<String> primeira = mcp(token, OBTER_CONTEXTO, null);
        HttpResponse<String> segunda = mcp(token, OBTER_CONTEXTO, null);

        assertThat(primeira.statusCode()).as(carga(token) + " " + primeira.body()).isEqualTo(200);
        assertThat(segunda.statusCode()).isEqualTo(200);
        JsonNode resultado = json.readTree(primeira.body()).get("result");
        assertThat(resultado.path("isError").asBoolean()).isFalse();
        JsonNode contexto = json.readTree(resultado.get("content").get(0).get("text").asString());
        assertThat(contexto.get("fuso").asString()).isEqualTo("America/Sao_Paulo");
        assertThat(contexto.get("moeda").asString()).isEqualTo("BRL");
        // O conteúdo é conferido em CapacidadesEscritaTest; aqui o banco é compartilhado com outros testes.
        assertThat(contexto.get("meios_pagamento").isArray()).isTrue();
        assertThat(contexto.get("contas").isArray()).isTrue();
        assertThat(banco.fetchCount(DONO, DONO.SUJEITO_OIDC.eq(sujeito(token)))).isEqualTo(1);
        assertSemIndexacao(primeira);
    }

    @Test
    void listaDeToolsTrazObterContextoComoLeitura() throws Exception {
        HttpResponse<String> resposta = mcp(Tokens.doGui("fin:read"), LISTAR_TOOLS, null);

        assertThat(resposta.statusCode()).isEqualTo(200);
        JsonNode tool = null;
        for (JsonNode candidata : json.readTree(resposta.body()).get("result").get("tools")) {
            if (candidata.get("name").asString().equals("obter_contexto")) {
                tool = candidata;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(tool.get("annotations").get("destructiveHint").asBoolean()).isFalse();
    }

    @Test
    void origemNaoPermitidaERecusada() throws Exception {
        HttpResponse<String> resposta = mcp(Tokens.doGui("fin:read"), OBTER_CONTEXTO, "https://site-malicioso.example");

        assertThat(resposta.statusCode()).isEqualTo(403);
    }

    @Test
    void rotaInexistenteTambemSaiSemIndexacao() throws Exception {
        assertSemIndexacao(get("/nao-existe"));
        assertSemIndexacao(get("/actuator/health"));
    }

    private HttpResponse<String> mcp(String token, String corpo, String origem) throws IOException, InterruptedException {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        if (origem != null) {
            pedido.header("Origin", origem);
        }
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String caminho) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String sujeito(String token) {
        return json.readTree(carga(token)).get("sub").asString();
    }

    private static String carga(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static void assertSemIndexacao(HttpResponse<String> resposta) {
        assertThat(resposta.headers().firstValue("X-Robots-Tag")).hasValue(FiltroSemIndexacao.X_ROBOTS_TAG);
        assertThat(resposta.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    }
}
