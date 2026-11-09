package tools.alicerce.financeiro.app;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/** Obtém tokens do Keycloak de teste pela concessão de senha (habilitada só nos testes). */
final class Tokens {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Tokens() {}

    static String obter(String cliente, String segredo, String usuario, String escopo) {
        Map<String, String> formulario = new LinkedHashMap<>();
        formulario.put("grant_type", "password");
        formulario.put("client_id", cliente);
        formulario.put("client_secret", segredo);
        formulario.put("username", usuario);
        formulario.put("password", KeycloakDeTeste.SENHA);
        formulario.put("scope", escopo);
        String corpo = formulario.entrySet().stream()
                .map(par -> par.getKey() + "=" + URLEncoder.encode(par.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(KeycloakDeTeste.emissor() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        try {
            HttpResponse<String> resposta = HTTP.send(pedido, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() != 200) {
                throw new IllegalStateException("Keycloak recusou o token: " + resposta.body());
            }
            return JSON.readTree(resposta.body()).get("access_token").asString();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static String daAna(String escopo) {
        return obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "ana", escopo);
    }

    /** Pelo cliente da interface web, o único que pode pedir fin:undo. */
    static String daInterface(String usuario, String escopo) {
        return obter("app-web", "", usuario, escopo);
    }

    static String doGui(String escopo) {
        return obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "gui", escopo);
    }
}
