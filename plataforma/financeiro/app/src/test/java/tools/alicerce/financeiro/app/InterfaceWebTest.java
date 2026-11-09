package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Testes de aceite pelo HTTP e pelo MCP: toda URL abre, com isolamento e recusa. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class, DadosDaInterface.class})
class InterfaceWebTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort int porta;
    @Autowired DadosDaInterface massa;
    @Autowired Vistas vistas;

    // A massa é uma só: o JUnit cria uma instância por teste, e o banco é o mesmo.
    private static DadosDaInterface.Enderecos gui;
    private static DadosDaInterface.Enderecos outro;
    private String token;

    @BeforeEach
    void preparar() throws Exception {
        token = Tokens.doGui("fin:read");
        get("/api/fin/vista?caminho=/fin/contas", token); // cria o dono do gui na primeira chamada
        if (gui == null) {
            gui = massa.preparar(massa.donoDoToken(token));
            outro = massa.preparar(massa.novoDono());
        }
    }

    @Test
    void cadaEnderecoAbreComOsComponentesEOsDadosEsperados() throws Exception {
        Map<String, List<String>> esperados = Map.ofEntries(
                Map.entry("contas", List.of("tabela", "link")),
                Map.entry("lancamentos", List.of("tabela")),
                Map.entry("operacoes", List.of("tabela")),
                Map.entry("lancamento", List.of("totais", "link_curto", "tabela")),
                Map.entry("conta", List.of("totais", "link_curto", "tabela", "texto")),
                Map.entry("fatura", List.of("totais", "link_curto", "tabela")),
                Map.entry("operacao", List.of("texto", "totais", "link_curto")),
                Map.entry("retrato", List.of("texto", "totais", "link_curto", "grafico", "tabela")),
                Map.entry("ritmo", List.of("texto", "totais", "link_curto", "cartao", "tabela")),
                Map.entry("cortes", List.of("texto", "totais", "link_curto", "cartao", "tabela")),
                Map.entry("projecao", List.of("texto", "totais", "link_curto", "cartao", "tabela")),
                Map.entry("prestacao", List.of("texto", "totais", "cartao", "grafico", "tabela", "lista")),
                Map.entry("relatorio", List.of("totais", "grafico", "tabela")),
                Map.entry("agenda", List.of("totais", "tabela", "link")),
                Map.entry("compromissos", List.of("tabela", "link")),
                Map.entry("compromisso", List.of("totais", "link_curto", "tabela")),
                Map.entry("meta", List.of("totais", "link_curto", "tabela")));
        Map<String, String> dados = Map.ofEntries(
                Map.entry("contas", "Itaú"),
                Map.entry("lancamentos", "Feira da semana"),
                Map.entry("operacoes", "gasto de"),
                Map.entry("lancamento", "123.45"),
                Map.entry("conta", "pendente"),
                Map.entry("fatura", "60.00"),
                Map.entry("operacao", "registrar_gasto"),
                Map.entry("retrato", "183.45"),
                Map.entry("ritmo", "183.45"),
                Map.entry("cortes", "777.00"),
                Map.entry("projecao", "654.32"),
                Map.entry("prestacao", "183.45"),
                Map.entry("relatorio", "183.45"),
                Map.entry("agenda", "Parcelamento IPTU"),
                Map.entry("compromissos", "PARC-0042"),
                Map.entry("compromisso", "155.00"),
                Map.entry("meta", "250.00"));

        assertThat(gui.caminhos().keySet()).isEqualTo(esperados.keySet());
        for (var endereco : gui.caminhos().entrySet()) {
            HttpResponse<String> resposta = abrir(endereco.getValue(), token);
            assertThat(resposta.statusCode()).as(endereco.getKey() + ": " + resposta.body()).isEqualTo(200);
            JsonNode vista = json.readTree(resposta.body());
            assertThat(vista.get("catalogo").asString()).isEqualTo("alicerce/v1");
            List<String> componentes = new ArrayList<>();
            vista.get("componentes").forEach(c -> componentes.add(c.get("componente").asString()));
            assertThat(componentes).as(endereco.getKey()).contains("pagina").containsAll(esperados.get(endereco.getKey()));
            assertThat(vista.get("dados").toString()).as(endereco.getKey()).contains(dados.get(endereco.getKey()));
            assertThat(resposta.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
    }

    @Test
    void aVistaCompostaComSaldosPelaFonteTrazAMoedaDeCadaConta() throws Exception {
        Vista vista = vistas.compor(gui.chamador(), new Vistas.Composicao(null, "r", List.of(
                Map.of("id", "r", "componente", "pagina", "titulo", "Saldos", "filhos", List.of("t")),
                Map.of("id", "t", "componente", "tabela", "legenda", "Contas", "origem", Map.of("caminho", "/ctx/contas"),
                        "colunas", List.of(Map.of("titulo", "Saldo", "campo", "saldo", "formato", "dinheiro")))),
                List.of(Map.of("id", "ctx", "capacidade", "obter_contexto"))));

        JsonNode dados = json.valueToTree(vista.dados());
        boolean achou = false;
        for (JsonNode conta : dados.get("ctx").get("contas")) {
            if (conta.get("nome").asString().equals("Wise")) {
                achou = true;
                assertThat(conta.get("moeda").asString()).isEqualTo("USD");
                assertThat(conta.get("saldo").asString()).isEqualTo("200.00");
            }
        }
        assertThat(achou).isTrue();
    }

    @Test
    void enderecoDeOutroDonoRespondeNaoEncontrado() throws Exception {
        for (var endereco : outro.caminhos().entrySet()) {
            if (endereco.getValue().contains("/fin/t/") || endereco.getValue().contains("/fin/a/") || endereco.getValue().contains("/fin/f/")
                    || endereco.getValue().contains("/fin/o/") || endereco.getValue().contains("/fin/s/") || endereco.getValue().contains("/fin/k/")
                    || endereco.getValue().contains("/fin/m/")) {
                HttpResponse<String> resposta = abrir(endereco.getValue(), token);
                assertThat(resposta.statusCode()).as(endereco.getKey()).isEqualTo(404);
                assertThat(resposta.body()).doesNotContain("Feira", "123.45");
            }
        }
        assertThat(abrir("/fin/t/ZZZZZZ", token).body()).doesNotContain("Feira");
        assertThat(abrir("/fin/t/ZZZZZZ", token).statusCode()).isEqualTo(404);
        assertThat(abrir("/fin/k/ZZZZZZ", token).statusCode()).isEqualTo(404);
        assertThat(abrir("/fin/m/ZZZZZZ", token).statusCode()).isEqualTo(404);
    }

    @Test
    void asContasDoDonoNaoMostramAsDoOutro() {
        Vista deUm = vistas.abrir(gui.chamador(), new Vistas.Pedido("/fin/lancamentos", null, null, null, null));
        Vista deOutro = vistas.abrir(outro.chamador(), new Vistas.Pedido("/fin/lancamentos", null, null, null, null));

        assertThat(deUm.dados().toString()).doesNotContain(outro.caminhos().get("lancamento").substring(7));
        assertThat(deOutro.dados().toString()).doesNotContain(gui.caminhos().get("lancamento").substring(7));
    }

    @Test
    void aApiExigeToken() throws Exception {
        assertThat(get("/api/fin/vista?caminho=/fin/contas", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/fin/vista?caminho=/fin/contas", Tokens.doGui("fin:write")).statusCode()).isEqualTo(403);
    }

    @Test
    void aCascaEPublicaSemDadosENaoIndexavel() throws Exception {
        for (String caminho : List.of("/", "/callback", "/fin/contas", "/fin/lancamentos", "/fin/operacoes", "/fin/relatorios", "/fin/agenda",
                "/fin/compromissos", gui.caminhos().get("compromisso"), gui.caminhos().get("lancamento"),
                gui.caminhos().get("conta"), gui.caminhos().get("fatura"), gui.caminhos().get("operacao"), gui.caminhos().get("retrato"), "/fin/n/ABCDEF")) {
            HttpResponse<String> resposta = get(caminho, null);
            assertThat(resposta.statusCode()).as(caminho).isEqualTo(200);
            assertThat(resposta.body()).contains("name=\"robots\"").doesNotContain("Feira");
            assertThat(resposta.headers().firstValue("X-Robots-Tag")).isPresent();
            assertThat(resposta.headers().firstValue("Content-Security-Policy").orElseThrow())
                    .contains("script-src 'self'", "frame-ancestors 'none'").doesNotContain("unsafe-inline", "unsafe-eval");
        }
        for (String caminho : List.of("/web/app.js", "/web/renderizador.js", "/web/estilo.css", "/web/casca.css", "/web/icone.svg", "/sw.js",
                "/manifest.webmanifest", "/web/config.json")) {
            HttpResponse<String> resposta = get(caminho, null);
            assertThat(resposta.statusCode()).as(caminho).isEqualTo(200);
            assertThat(resposta.body()).doesNotContain("cdn.", "googleapis", "unpkg");
        }
    }

    @Test
    void aVistaCompostaUsaSoDadosDoDominio() throws Exception {
        JsonNode resultado = chamarTool(Map.of(
                "raiz", "r",
                "fontes", List.of(Map.of("id", "lanc", "capacidade", "listar_lancamentos")),
                "componentes", List.of(
                        Map.of("id", "r", "componente", "pagina", "titulo", "Meus gastos", "filhos", List.of("t")),
                        Map.of("id", "t", "componente", "tabela", "legenda", "Gastos", "origem", Map.of("caminho", "/lanc/lancamentos"),
                                "colunas", List.of(Map.of("titulo", "Valor", "campo", "valor", "formato", "dinheiro"))))));

        assertThat(resultado.get("isError").asBoolean()).as(resultado.toString()).isFalse();
        assertThat(resultado.get("structuredContent").get("dados").get("lanc").toString()).contains("123.45");
    }

    @Test
    void oEnderecoNaToolAbreATelaPadrao() throws Exception {
        JsonNode resultado = chamarTool(Map.of("endereco", "https://app.alicerce.tools" + gui.caminhos().get("lancamento")));

        assertThat(resultado.get("isError").asBoolean()).as(resultado.toString()).isFalse();
        assertThat(resultado.get("structuredContent").toString()).contains("Feira da semana");
    }

    @Test
    void descricaoRuimERecusadaENaoChegaAoRenderizador() throws Exception {
        var fontes = List.of(Map.of("id", "lanc", "capacidade", "listar_lancamentos"));
        for (Object componente : List.of(
                Map.of("id", "x", "componente", "iframe", "src", "https://mal.example"),
                Map.of("id", "x", "componente", "texto", "valor", "<script>alert()</script>"),
                Map.of("id", "x", "componente", "texto", "valor", "Você gastou R$ 500"),
                Map.of("id", "x", "componente", "totais", "rotulo", "Totais", "itens", List.of(Map.of("rotulo", "Total", "valor", "500.00"))),
                Map.of("id", "x", "componente", "totais", "rotulo", "Totais", "itens", List.of(Map.of("rotulo", "Total", "valor", Map.of("caminho", "/inventado")))))) {
            JsonNode resultado = chamarTool(Map.of("raiz", "r", "fontes", fontes, "componentes", List.of(
                    Map.of("id", "r", "componente", "pagina", "titulo", "Vista", "filhos", List.of("x")), componente)));

            assertThat(resultado.get("isError").asBoolean()).as(componente.toString()).isTrue();
            assertThat(resultado.has("structuredContent")).isFalse();
            assertThat(resultado.toString()).contains("Vista recusada");
        }
    }

    @Test
    void asVistasCompostasNaoAceitamFonteForaDaLista() {
        assertThatThrownBy(() -> vistas.compor(gui.chamador(), new Vistas.Composicao(null, "r",
                List.of(Map.of("id", "r", "componente", "pagina", "titulo", "A", "filhos", List.of("r"))),
                List.of(Map.of("id", "a", "capacidade", "registrar_gasto"))))).isInstanceOf(ErroTipado.class).hasMessageContaining("fora da lista");
    }

    @Test
    void aConfiguracaoNaoFicaEmCacheETrazOsEscoposDasEscritas() throws Exception {
        HttpResponse<String> resposta = get("/web/config.json", null);

        assertThat(resposta.headers().firstValue("Cache-Control").orElse("")).contains("no-cache").doesNotContain("max-age=300");
        assertThat(json.readTree(resposta.body()).get("escopo_desfazer").asString()).isEqualTo("openid fin:undo");
        assertThat(json.readTree(resposta.body()).get("escopo_anexo").asString()).isEqualTo("openid fin:attach");
    }

    @Test
    void vistaCompostaNaoMostraNemAcionaODesfazer() throws Exception {
        var fontes = List.of(Map.of("id", "ops", "capacidade", "listar_operacoes"));
        for (Object componente : List.of(
                Map.of("id", "x", "componente", "botao", "rotulo", "Desfazer", "url", Map.of("caminho", "/ops/operacoes/0/url")),
                Map.of("id", "x", "componente", "desfazer", "alvo", Map.of("caminho", "/ops/operacoes/0/id")),
                Map.of("id", "x", "componente", "texto", "valor", "Ok", "acao", "desfazer"))) {
            JsonNode resultado = chamarTool(Map.of("raiz", "r", "fontes", fontes, "componentes", List.of(
                    Map.of("id", "r", "componente", "pagina", "titulo", "Vista", "filhos", List.of("x")), componente)));

            assertThat(resultado.get("isError").asBoolean()).as(componente.toString()).isTrue();
            assertThat(resultado.has("structuredContent")).isFalse();
        }
        assertThatThrownBy(() -> vistas.compor(gui.chamador(), new Vistas.Composicao(null, "r",
                List.of(Map.of("id", "r", "componente", "pagina", "titulo", "A", "filhos", List.of("r"))),
                List.of(Map.of("id", "a", "capacidade", "desfazer"))))).isInstanceOf(ErroTipado.class).hasMessageContaining("fora da lista");

        // A página do assistente não tem o código da ação: nada ali pede a API nem o escopo.
        String pagina = rpc("resources/read", "{\"uri\":\"ui://alicerce/vista\"}").get("contents").get(0).get("text").asString();
        assertThat(pagina).doesNotContain("/api/", "fetch(", "fin:undo", "fin:attach", "desfazer", "anexavel");
        assertThat(get("/web/renderizador.js", null).body()).doesNotContain("/api/", "fetch(", "fin:undo", "fin:attach", "desfazer", "anexavel");
        assertThat(get("/web/app.js", null).body()).contains("/desfazer", "/anexo");
    }

    @Test
    void aFerramentaEORecursoSeguemOMcpApps() throws Exception {
        JsonNode iniciado = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        assertThat(iniciado.get("capabilities").get("extensions").get("io.modelcontextprotocol/ui").toString()).contains("text/html;profile=mcp-app");

        List<String> ligadas = new ArrayList<>();
        rpc("tools/list", "{}").get("tools").forEach(t -> {
            if (t.has("_meta") && t.get("_meta").has("ui")) {
                ligadas.add(t.get("name").asString() + ":" + t.get("_meta").get("ui").get("resourceUri").asString());
            }
        });
        assertThat(ligadas).containsExactly("mostrar_vista:ui://alicerce/vista");

        JsonNode conteudo = rpc("resources/read", "{\"uri\":\"ui://alicerce/vista\"}").get("contents").get(0);
        assertThat(conteudo.get("mimeType").asString()).isEqualTo("text/html;profile=mcp-app");
        assertThat(conteudo.get("text").asString()).startsWith("<!doctype html>").contains("AlicerceVista", "name=\"robots\"")
                .doesNotContain("/*RENDERIZADOR*/", "/*ESTILO*/", "/*MCP_APP*/", "<script src", "<link ");
    }

    private JsonNode chamarTool(Map<String, Object> argumentos) throws Exception {
        return rpc("tools/call", json.writeValueAsString(Map.of("name", "mostrar_vista", "arguments", argumentos)));
    }

    private JsonNode rpc(String metodo, String parametros) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + metodo + "\",\"params\":" + parametros + "}"))
                .build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        JsonNode mensagem = json.readTree(resposta.body());
        assertThat(mensagem.has("error")).as(resposta.body()).isFalse();
        return mensagem.get("result");
    }

    private HttpResponse<String> abrir(String endereco, String tokenUsado) throws Exception {
        int consulta = endereco.indexOf('?');
        String caminho = consulta < 0 ? endereco : endereco.substring(0, consulta);
        String resto = consulta < 0 ? "" : "&" + endereco.substring(consulta + 1);
        return get("/api/fin/vista?caminho=" + URLEncoder.encode(caminho, StandardCharsets.UTF_8) + resto, tokenUsado);
    }

    private HttpResponse<String> get(String caminho, String tokenUsado) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho));
        if (tokenUsado != null) {
            pedido.header("Authorization", "Bearer " + tokenUsado);
        }
        return http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }
}
