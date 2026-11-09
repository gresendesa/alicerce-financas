package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Testes de aceite pela API: tokens reais do cliente app-web com fin:attach, resultado conferido no banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class, DadosDaInterface.class, MinioDeTeste.class})
class AnexoInterfaceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 5, 6, 7, 8};

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort int porta;
    @Autowired DadosDaInterface massa;
    @Autowired DSLContext banco;
    @Autowired ResolverReferencia resolver;
    @Autowired CorrigirLancamento corrigir;

    private static Chamador bia;
    private static Chamador outro;
    private String tokenAnexo;

    @BeforeEach
    void preparar() throws Exception {
        tokenAnexo = Tokens.daInterface("bia", "fin:attach");
        if (bia == null) {
            String leitura = Tokens.daInterface("bia", "fin:read");
            enviar(HttpRequest.newBuilder(uri("/api/fin/vista?caminho=/fin/contas")).header("Authorization", "Bearer " + leitura).GET());
            bia = massa.preparar(massa.donoDoToken(leitura)).chamador();
            outro = massa.preparar(massa.novoDono()).chamador();
        }
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, bia.dono())).isEmpty();
    }

    @Test
    void comFinAttachEmiteOLinkEOEnvioApareceComoDaInterface() throws Exception {
        LancamentoRegistrado gasto = massa.gasto(bia, "Almoço com foto").valor();

        HttpResponse<String> resposta = pedirLink(gasto.id().substring(2), tokenAnexo);

        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode corpo = json.readTree(resposta.body());
        String url = corpo.get("url_anexo").asString();
        assertThat(url).contains("/fin/anexo/");
        assertThat(corpo.get("valida_ate").asString()).isNotBlank();
        var emissao = banco.fetchOne("""
                select o.id, o.cliente, o.capacidade from operacao o join efeito_operacao e on e.operacao_id = o.id
                where o.dono_id = ? and e.tipo = 'token_anexo_emitido' order by o.criada_em desc limit 1""", bia.dono());
        assertThat(emissao.get(1, String.class)).isEqualTo("app-web");
        assertThat(emissao.get(2, String.class)).isEqualTo("anexar_comprovante");
        var evento = banco.fetchOne("select cliente, escopos::text, resultado from evento_auditoria where operacao_id = ?", emissao.get(0, UUID.class));
        assertThat(evento.get(1, String.class)).contains("fin:attach").doesNotContain("fin:write");
        assertThat(evento.get(2, String.class)).isEqualTo("sucesso");
        assertThat(banco.fetch("select r.resultado::text from resultado_operacao r where r.operacao_id = ?", emissao.get(0, UUID.class))
                .getValues(0, String.class)).noneSatisfy(r -> assertThat(r).contains(url.substring(url.lastIndexOf('/') + 1)));

        HttpResponse<String> envio = enviarPelaPagina(url, "foto");

        assertThat(envio.statusCode()).as(envio.body()).isEqualTo(201);
        assertThat(envio.body()).contains("Foto recebida", "Voltar ao lançamento", gasto.url());
        assertThat(banco.fetchOne("""
                select cliente from operacao where dono_id = ? and capacidade = 'enviar_comprovante'
                order by criada_em desc limit 1""", bia.dono()).get(0, String.class)).isEqualTo("app-web");
        assertThat(resolver.executar(bia, gasto.id()).fotos()).hasSize(1);
    }

    @Test
    void oDesfazerDaInterfaceRevertOEnvio() throws Exception {
        LancamentoRegistrado gasto = massa.gasto(bia, "Envio a desfazer").valor();
        String url = json.readTree(pedirLink(gasto.id().substring(2), tokenAnexo).body()).get("url_anexo").asString();
        assertThat(enviarPelaPagina(url, "comprovante").statusCode()).isEqualTo(201);
        String envio = banco.fetchOne("""
                select substring(r.codigo from 3) from operacao o join referencia_curta r on r.entidade_id = o.id
                where o.dono_id = ? and o.capacidade = 'enviar_comprovante' order by o.criada_em desc limit 1""", bia.dono())
                .get(0, String.class);
        String desfazer = Tokens.daInterface("bia", "fin:undo");

        HttpResponse<String> previa = post("/api/fin/operacoes/" + envio + "/desfazer/previa", desfazer, "{}");
        assertThat(previa.statusCode()).as(previa.body()).isEqualTo(200);
        HttpResponse<String> aplicada = post("/api/fin/operacoes/" + envio + "/desfazer", desfazer, json.writeValueAsString(Map.of(
                "confirmacao", json.readTree(previa.body()).get("confirmacao").asString(), "chave_idempotencia", UUID.randomUUID().toString())));

        assertThat(aplicada.statusCode()).as(aplicada.body()).isEqualTo(200);
        assertThat(resolver.executar(bia, gasto.id()).comprovantes()).isNull();
    }

    @Test
    void tokenSemFinAttachNaoEmiteLink() throws Exception {
        LancamentoRegistrado gasto = massa.gasto(bia, "Protegido").valor();
        long links = contarLinks();

        assertThat(pedirLink(gasto.id().substring(2), null).statusCode()).isEqualTo(401);
        for (String token : List.of(Tokens.daInterface("bia", "fin:read"), Tokens.daInterface("bia", "fin:undo"),
                Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure"))) {
            assertThat(pedirLink(gasto.id().substring(2), token).statusCode()).isEqualTo(403);
        }
        assertThat(contarLinks()).isEqualTo(links);
    }

    @Test
    void oTokenDeAnexoNaoFazNenhumaOutraEscritaNemLe() throws Exception {
        HttpResponse<String> leitura = enviar(HttpRequest.newBuilder(uri("/api/fin/vista?caminho=/fin/contas"))
                .header("Authorization", "Bearer " + tokenAnexo).GET());
        assertThat(leitura.statusCode()).isEqualTo(403);
        String operacao = banco.fetchOne("""
                select substring(r.codigo from 3) from operacao o join referencia_curta r on r.entidade_id = o.id
                where o.dono_id = ? order by o.criada_em desc limit 1""", bia.dono()).get(0, String.class);
        assertThat(post("/api/fin/operacoes/" + operacao + "/desfazer/previa", tokenAnexo, "{}").statusCode()).isEqualTo(403);

        for (String ferramenta : List.of("registrar_gasto", "registrar_ganho", "registrar_transferencia", "corrigir_lancamento",
                "anexar_comprovante", "definir_conta", "definir_meio_pagamento", "definir_ambito", "desfazer")) {
            assertThat(ferramenta(tokenAnexo, ferramenta).statusCode()).as(ferramenta).isEqualTo(403);
        }
    }

    @Test
    void soAInterfaceConsegueOEscopo() {
        assertThatThrownBy(() -> Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:attach"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid_scope");
    }

    @Test
    void lancamentoDeOutroDonoInexistenteOuAnuladoNaoGanhaLink() throws Exception {
        LancamentoRegistrado alheio = massa.gasto(outro, "De outra conta").valor();
        long links = contarLinks();

        HttpResponse<String> resposta = pedirLink(alheio.id().substring(2), tokenAnexo);
        assertThat(resposta.statusCode()).isEqualTo(404);
        assertThat(resposta.body()).doesNotContain("De outra conta");
        assertThat(pedirLink("ZZZZZZ", tokenAnexo).statusCode()).isEqualTo(404);

        LancamentoRegistrado anulado = massa.gasto(bia, "Anulado").valor();
        corrigir.executar(bia, new CorrigirLancamento.Entrada(UUID.randomUUID().toString(), null, anulado.id(), "anular", null, "errado", null));
        assertThat(pedirLink(anulado.id().substring(2), tokenAnexo).statusCode()).isEqualTo(409);
        assertThat(contarLinks()).isEqualTo(links);
    }

    private long contarLinks() {
        return banco.fetchOne("select count(*) from token_anexo where dono_id = ?", bia.dono()).get(0, Long.class);
    }

    private HttpResponse<String> pedirLink(String codigo, String token) throws Exception {
        return post("/api/fin/lancamentos/" + codigo + "/anexo", token, "");
    }

    private HttpResponse<String> enviarPelaPagina(String url, String natureza) throws Exception {
        String limite = "limite-" + UUID.randomUUID();
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        corpo.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"natureza\"\r\n\r\n" + natureza + "\r\n"
                + "--" + limite + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"a.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        corpo.write(PNG);
        corpo.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return enviar(HttpRequest.newBuilder(uri(URI.create(url).getRawPath()))
                .header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())));
    }

    private HttpResponse<String> ferramenta(String token, String nome) throws Exception {
        return enviar(HttpRequest.newBuilder(uri("/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + nome + "\",\"arguments\":{}}}")));
    }

    private HttpResponse<String> post(String caminho, String token, String corpo) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(uri(caminho)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return enviar(pedido);
    }

    private HttpResponse<String> enviar(HttpRequest.Builder pedido) throws Exception {
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String caminho) {
        return URI.create("http://localhost:" + porta + caminho);
    }
}
