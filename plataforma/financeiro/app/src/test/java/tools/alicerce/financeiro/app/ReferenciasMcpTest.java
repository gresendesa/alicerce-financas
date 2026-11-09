package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code resolver_referencia} resolve todo tipo de id curto, como a interface web. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class})
class ReferenciasMcpTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int porta;

    @Test
    void contaPeloIdCurtoVemComSaldoMarcoEEndereco() throws Exception {
        String token = Tokens.doGui("fin:read fin:structure");
        String nome = "Nubank " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode definida = escrever(token, "definir_conta", """
                {"chave_idempotencia":"%s","tipo":"corrente","nome":"%s","saldo_abertura":"250.00","data_abertura":"2026-10-26"}"""
                .formatted(UUID.randomUUID(), nome));
        String id = definida.get("resultado").get("id").asString();
        assertThat(id).startsWith("a-");

        JsonNode conta = ler(Tokens.doGui("fin:read"), id);

        assertThat(conta.get("id").asString()).isEqualTo(id);
        assertThat(conta.get("nome").asString()).isEqualTo(nome);
        assertThat(conta.get("tipo").asString()).isEqualTo("corrente");
        assertThat(conta.get("moeda").asString()).isEqualTo("BRL");
        assertThat(conta.get("saldo").asString()).isEqualTo("250.00");
        assertThat(conta.get("marco").asString()).isEqualTo("2026-10-26");
        assertThat(conta.get("arquivada").asBoolean()).isFalse();
        assertThat(conta.get("url").asString()).endsWith("/fin/a/" + id.substring(2));
        // A URL colada resolve o mesmo, como o id completo.
        assertThat(ler(token, conta.get("url").asString()).get("id").asString()).isEqualTo(id);
        assertThat(ler(token, "fin:" + id).get("id").asString()).isEqualTo(id);
    }

    @Test
    void operacaoPeloIdCurtoVemComoEmListarOperacoes() throws Exception {
        String token = Tokens.doGui("fin:read fin:structure");
        JsonNode definida = escrever(token, "definir_conta", """
                {"chave_idempotencia":"%s","tipo":"categoria","nome":"Categoria %s"}""".formatted(UUID.randomUUID(), UUID.randomUUID()));
        String id = definida.get("operacao").get("id").asString();

        JsonNode operacao = ler(token, id);

        assertThat(operacao.get("id").asString()).isEqualTo(id);
        assertThat(operacao.get("capacidade").asString()).isEqualTo("definir_conta");
        assertThat(operacao.get("estado").asString()).isEqualTo("aplicada");
    }

    @Test
    void nenhumTipoDeIdCaiNumaRecusaGenerica() throws Exception {
        String token = Tokens.doGui("fin:read");
        for (TipoReferencia tipo : TipoReferencia.values()) {
            String ref = tipo.prefixo() + "-ZZZZZZ";
            JsonNode resultado = resultado(chamar(token, "resolver_referencia", "{\"ref\":\"%s\"}".formatted(ref)));

            assertThat(resultado.get("isError").asBoolean()).as(ref).isTrue();
            JsonNode erro = texto(resultado).get("erro");
            assertThat(erro.get("codigo").asString()).as(ref).isEqualTo("nao_encontrado");
            assertThat(erro.get("mensagem").asString()).as(ref).contains(ref).doesNotContain("Por enquanto");
        }
    }

    @Test
    void prefixoQueNaoEDoFinanceiroDizQuaisExistem() throws Exception {
        JsonNode resultado = resultado(chamar(Tokens.doGui("fin:read"), "resolver_referencia", "{\"ref\":\"x-ZZZZZZ\"}"));

        assertThat(resultado.get("isError").asBoolean()).isTrue();
        assertThat(texto(resultado).get("erro").get("mensagem").asString()).contains("a- conta", "o- operação");
    }

    private JsonNode ler(String token, String ref) throws Exception {
        JsonNode resultado = resultado(chamar(token, "resolver_referencia", "{\"ref\":\"%s\"}".formatted(ref)));
        assertThat(resultado.path("isError").asBoolean(false)).as(resultado.toString()).isFalse();
        return texto(resultado);
    }

    private JsonNode escrever(String token, String tool, String argumentos) throws Exception {
        JsonNode resultado = resultado(chamar(token, tool, argumentos));
        assertThat(resultado.path("isError").asBoolean(false)).as(resultado.toString()).isFalse();
        return texto(resultado);
    }

    private JsonNode resultado(HttpResponse<String> resposta) {
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        return json.readTree(resposta.body()).get("result");
    }

    private JsonNode texto(JsonNode resultado) {
        return json.readTree(resultado.get("content").get(0).get("text").asString());
    }

    private HttpResponse<String> chamar(String token, String tool, String argumentos) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
                        .formatted(tool, argumentos))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
