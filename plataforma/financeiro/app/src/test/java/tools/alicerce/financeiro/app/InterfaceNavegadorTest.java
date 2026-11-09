package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.deque.html.axecore.results.Results;
import com.deque.html.axecore.selenium.AxeBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jooq.DSLContext;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A interface num navegador real: login com PKCE no Keycloak versionado, cada tela renderizada e o
 * verificador de WCAG AA nos dois temas. O navegador fica na rede do host: é onde a aplicação e o
 * Keycloak de teste estão em localhost, que também é contexto seguro para a criptografia do PKCE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class, DadosDaInterface.class, MinioDeTeste.class})
class InterfaceNavegadorTest {

    private static final int PORTA_APP = portaLivre();
    private static final int PORTA_NAVEGADOR = portaLivre();
    private static final List<String> REGRAS = List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa");

    @SuppressWarnings("resource") // parado pelo Ryuk ao fim da JVM, como os outros contêineres de teste
    private static final GenericContainer<?> CHROMIUM = new GenericContainer<>(System.getProperty("alicerce.imagem.chromium"))
            .withNetworkMode("host")
            .withSharedMemorySize(1024L * 1024 * 1024)
            .withEnv(Map.of("SE_OPTS", "--port " + PORTA_NAVEGADOR, "SE_START_VNC", "false", "SE_START_NO_VNC", "false", "SE_START_XVFB", "false",
                    // A sessão logada fica aberta enquanto outro teste abre a dele.
                    "SE_NODE_MAX_SESSIONS", "3", "SE_NODE_OVERRIDE_MAX_SESSIONS", "true", "SE_NODE_SESSION_TIMEOUT", "1200"))
            .waitingFor(Wait.forLogMessage(".*Started Selenium.*", 1).withStartupTimeout(Duration.ofMinutes(3)));

    private static WebDriver claro;
    private static DadosDaInterface.Enderecos ana;

    @Autowired DadosDaInterface massa;
    @Autowired tools.alicerce.financeiro.aplicacao.PrestarContas prestarContas;
    @Autowired DSLContext banco;

    @DynamicPropertySource
    static void porta(DynamicPropertyRegistry registro) {
        registro.add("server.port", () -> PORTA_APP);
        registro.add("alicerce.financeiro.endereco-web", () -> "http://localhost:" + PORTA_APP);
    }

    private static int portaLivre() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void preparar() throws Exception {
        if (claro != null) {
            return;
        }
        CHROMIUM.start();
        String token = Tokens.daAna("fin:read");
        HttpClient.newHttpClient().send(pedido("/api/fin/vista?caminho=/fin/contas", token).build(), HttpResponse.BodyHandlers.ofString());
        ana = massa.preparar(massa.donoDoToken(token));
        claro = navegador();
        entrar(claro);
    }

    @AfterAll
    static void fechar() {
        if (claro != null) {
            claro.quit();
        }
    }

    @Test
    void oLoginComPkceDevolveAoEnderecoPedidoESoComFinRead() {
        WebDriver navegador = navegador();
        try {
            navegador.get(url(ana.caminhos().get("lancamento")));
            esperar(navegador).until(ExpectedConditions.presenceOfElementLocated(By.id("username")));
            assertThat(navegador.getCurrentUrl()).contains("code_challenge_method=S256").contains("scope=openid+fin%3Aread").contains("client_id=app-web");
            preencherLogin(navegador);
            WebElement titulo = esperarTela(navegador);

            assertThat(titulo.getText()).isEqualTo("Feira da semana");
            assertThat(navegador.getCurrentUrl()).isEqualTo(url(ana.caminhos().get("lancamento")));
        } finally {
            navegador.quit();
        }
    }

    @Test
    void cadaTelaTemOsComponentesEOsDadosEsperadosSemViolarWcagAa() {
        Map<String, List<String>> esperados = Map.ofEntries(
                Map.entry("contas", List.of("tabela", "link")),
                Map.entry("lancamentos", List.of("tabela")),
                Map.entry("operacoes", List.of("tabela")),
                Map.entry("lancamento", List.of("totais", "link_curto", "tabela")),
                Map.entry("conta", List.of("totais", "link_curto", "tabela", "texto")),
                Map.entry("fatura", List.of("totais", "link_curto", "tabela")),
                Map.entry("operacao", List.of("texto", "totais", "link_curto")),
                Map.entry("retrato", List.of("totais", "grafico", "cartao", "tabela")),
                Map.entry("ritmo", List.of("totais", "link_curto", "cartao", "tabela")),
                Map.entry("cortes", List.of("totais", "link_curto", "cartao", "tabela")),
                Map.entry("projecao", List.of("totais", "link_curto", "cartao", "tabela")),
                Map.entry("prestacao", List.of("totais", "grafico", "cartao", "tabela", "lista")),
                Map.entry("relatorio", List.of("totais", "grafico", "cartao", "tabela")),
                Map.entry("agenda", List.of("totais", "tabela", "link")),
                Map.entry("compromissos", List.of("tabela", "link")),
                Map.entry("compromisso", List.of("totais", "link_curto", "tabela")),
                Map.entry("meta", List.of("totais", "link_curto", "tabela")));
        Map<String, String> textos = Map.ofEntries(
                Map.entry("contas", "Itaú"),
                Map.entry("lancamentos", "R$ 123,45"),
                Map.entry("operacoes", "gasto de R$ 123,45"),
                Map.entry("lancamento", "R$ 123,45"),
                Map.entry("conta", "diferença pendente"),
                Map.entry("fatura", "R$ 60,00"),
                Map.entry("operacao", "registrar_gasto"),
                Map.entry("retrato", "R$ 183,45"),
                Map.entry("ritmo", "R$ 183,45"),
                Map.entry("cortes", "R$ 777,00"),
                Map.entry("projecao", "R$ 654,32"),
                Map.entry("prestacao", "R$ 183,45"),
                Map.entry("relatorio", "R$ 183,45"),
                Map.entry("agenda", "Parcelamento IPTU"),
                Map.entry("compromissos", "PARC-0042"),
                Map.entry("compromisso", "R$ 155,00"),
                Map.entry("meta", "R$ 250,00"));

        assertThat(ana.caminhos().keySet()).isEqualTo(esperados.keySet());
        for (var endereco : ana.caminhos().entrySet()) {
            abrir(claro, endereco.getValue());
            for (String componente : esperados.get(endereco.getKey())) {
                assertThat(claro.findElements(By.cssSelector("[data-componente=" + componente + "]"))).as(endereco.getKey() + " " + componente).isNotEmpty();
            }
            String texto = claro.findElement(By.id("conteudo")).getText().replaceAll("\\p{Z}", " ");
            assertThat(texto).as(endereco.getKey()).contains(textos.get(endereco.getKey()));
            // A tela é do dono: nada do que o servidor diz só ao assistente.
            assertThat(texto).as(endereco.getKey()).doesNotContain("Repasse", "repasse", "sugira", "apresente-os", "diga qual", "Mostre as duas");
            semViolacoes(claro, endereco.getKey());
            assertThat(claro.findElement(By.cssSelector("meta[name=robots]")).getDomAttribute("content")).contains("noindex");
        }
    }

    @Test
    void cadaValorSaiNaMoedaDaPropriaLinhaSemViolarWcagAa() {
        abrir(claro, "/fin/contas");
        assertThat(telaEmTexto()).contains("USD 200,00", "EUR 50,00");
        semViolacoes(claro, "contas");

        abrir(claro, ana.emMoedaEstrangeira().get("conta"));
        // O extrato da conta em USD mostra o câmbio pelo lado dela, não pelos R$ 550,00.
        assertThat(telaEmTexto()).contains("USD 200,00", "USD 100,00").doesNotContain("R$ 550,00");
        semViolacoes(claro, "conta em USD");

        abrir(claro, ana.emMoedaEstrangeira().get("cambio"));
        assertThat(telaEmTexto()).contains("R$ 550,00", "USD 100,00", "-USD 100,00", "-R$ 550,00", "5,5", "08/10/2026")
                .doesNotContain("100.00");
        semViolacoes(claro, "câmbio");

        abrir(claro, ana.caminhos().get("relatorio"));
        assertThat(telaEmTexto()).contains("Saldos convertidos", "USD 200,00", "5,5", "R$ 1.100,00", "fora do total", "EUR 50,00")
                .doesNotContain("nunca some");
        semViolacoes(claro, "relatório com saldos convertidos");

        abrir(claro, ana.caminhos().get("retrato"));
        assertThat(telaEmTexto()).contains("USD 200,00", "R$ 1.100,00");
        semViolacoes(claro, "retrato com saldos convertidos");
    }

    private String telaEmTexto() {
        return claro.findElement(By.id("conteudo")).getText().replaceAll("\\p{Z}", " ");
    }

    @Test
    void naLarguraDeCelularOsSaldosConvertidosNaoEstouramAPagina() {
        WebDriver celular = navegador();
        try {
            entrar(celular);
            celular.manage().window().setSize(new org.openqa.selenium.Dimension(360, 740));
            abrir(celular, ana.caminhos().get("relatorio"));
            semSobra(celular, "relatório");
            semViolacoes(celular, "relatório em 360 px");
        } finally {
            celular.quit();
        }
    }

    @Test
    void oTemaEscuroTambemPassa() {
        ChromeOptions opcoes = opcoes().addArguments("--blink-settings=preferredColorScheme=0");
        WebDriver escuro = conectar(opcoes);
        try {
            entrar(escuro);
            assertThat(((JavascriptExecutor) escuro).executeScript("return matchMedia('(prefers-color-scheme: dark)').matches")).isEqualTo(true);
            for (var endereco : ana.caminhos().entrySet()) {
                abrir(escuro, endereco.getValue());
                semViolacoes(escuro, "escuro " + endereco.getKey());
            }
            escuro.manage().window().setSize(new Dimension(360, 740));
            for (var endereco : ana.caminhos().entrySet()) {
                abrir(escuro, endereco.getValue());
                assertThat(palavrasQuebradas(escuro)).as("escuro 360 px " + endereco.getKey()).isEmpty();
                semViolacoes(escuro, "escuro 360 px " + endereco.getKey());
            }
        } finally {
            escuro.quit();
        }
    }

    @Test
    void aTrilhaDestacaARecusadaPelaCorEPeloTextoNosDoisTemasENoCelular() {
        massa.gastoRecusado(ana.chamador());
        abrir(claro, "/fin/operacoes");
        conferirTrilha(claro, "claro");

        WebDriver escuro = conectar(opcoes().addArguments("--blink-settings=preferredColorScheme=0"));
        try {
            entrar(escuro);
            abrir(escuro, "/fin/operacoes");
            conferirTrilha(escuro, "escuro");
            escuro.manage().window().setSize(new Dimension(360, 740));
            abrir(escuro, "/fin/operacoes");
            conferirTrilha(escuro, "escuro 360 px");
            Object sobra = ((JavascriptExecutor) escuro).executeScript("return document.documentElement.scrollWidth - document.documentElement.clientWidth");
            assertThat(((Number) sobra).intValue()).isLessThanOrEqualTo(0);
            assertThat(palavrasQuebradas(escuro)).isEmpty();
        } finally {
            escuro.quit();
        }
    }

    private void conferirTrilha(WebDriver navegador, String tema) {
        List<WebElement> destacadas = navegador.findElements(By.cssSelector("tr.destaque"));
        assertThat(destacadas).as(tema).isNotEmpty();
        // Nunca só pela cor (WCAG 1.4.1): a linha diz "Recusada" e o motivo.
        assertThat(destacadas.get(0).getText()).as(tema).contains("Recusada", "faltaram campos obrigatórios", "Claude");
        String comum = navegador.findElement(By.cssSelector("tbody tr:not(.destaque)")).getCssValue("background-color");
        assertThat(destacadas.get(0).getCssValue("background-color")).as(tema).isNotEqualTo(comum);
        assertThat(navegador.findElement(By.id("conteudo")).getText()).as(tema).containsPattern("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}");
        assertThat(navegador.findElements(By.linkText("Últimos 7 dias"))).as(tema).hasSize(1);
        semViolacoes(navegador, "trilha " + tema);
    }

    @Test
    void daOperacaoSeChegaAoLancamentoEDeleDeVoltaAOperacao() {
        abrir(claro, ana.caminhos().get("operacao"));
        assertThat(telaEmTexto()).contains("Registros afetados", "lançamento", "criado", "Claude");
        String lancamento = ana.caminhos().get("lancamento").substring("/fin/t/".length());
        claro.findElement(By.partialLinkText(lancamento)).click();
        esperar(claro).until(ExpectedConditions.textToBe(By.tagName("h1"), "Feira da semana"));
        claro.findElement(By.linkText("Operação que registrou")).click();
        esperar(claro).until(ExpectedConditions.textToBe(By.tagName("h1"), "Operação"));
        assertThat(claro.getCurrentUrl()).isEqualTo(url(ana.caminhos().get("operacao")));
        semViolacoes(claro, "operação com registros afetados");
    }

    @Test
    void naLarguraDeCelularNadaEstouraAPagina() {
        claro.manage().window().setSize(new Dimension(360, 740));
        try {
            for (String caminho : ana.caminhos().values()) {
                abrir(claro, caminho);
                Object sobra = ((JavascriptExecutor) claro).executeScript("return document.documentElement.scrollWidth - document.documentElement.clientWidth");
                assertThat(((Number) sobra).intValue()).as(caminho).isLessThanOrEqualTo(0);
                // A página não estourar não basta: a tabela espremida quebrava palavra letra a letra.
                assertThat(palavrasQuebradas(claro)).as(caminho).isEmpty();
                assertThat(((JavascriptExecutor) claro).executeScript(CABECALHO_NUMA_LINHA)).as(caminho + " cabeçalho").isEqualTo(true);
                assertThat(((JavascriptExecutor) claro).executeScript(ROTULOS_DESALINHADOS)).as(caminho + " rótulos à esquerda").isEqualTo(java.util.List.of());
                semViolacoes(claro, "360 px " + caminho);
            }
        } finally {
            claro.manage().window().setSize(new Dimension(1280, 900));
        }
    }

    @Test
    void osLinksNavegamSemRecarregarEHaBotoesDeCopiar() {
        abrir(claro, "/fin/lancamentos");
        ((JavascriptExecutor) claro).executeScript("window.semRecarregar = true");
        claro.findElement(By.linkText("Feira da semana")).click();
        esperar(claro).until(ExpectedConditions.textToBe(By.tagName("h1"), "Feira da semana"));

        assertThat(((JavascriptExecutor) claro).executeScript("return window.semRecarregar")).isEqualTo(true);
        assertThat(claro.findElements(By.xpath("//button[text()='Copiar id']"))).hasSize(1);
        assertThat(claro.findElements(By.xpath("//button[text()='Copiar link']"))).hasSize(1);
        assertThat(claro.findElement(By.tagName("h1")).equals(((JavascriptExecutor) claro).executeScript("return document.activeElement"))).isTrue();
    }

    /** PNG de 1 x 1 pixel, para o navegador desenhar de verdade. */
    private static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    @Test
    void oComprovanteAbrePelaTelaDoLancamentoEPelaDoComprovante() throws Exception {
        var gasto = massa.gastoComFoto(ana.chamador(), "Compra com nota").valor();
        String envio = gasto.urlAnexo().substring(gasto.urlAnexo().indexOf("/fin/anexo/"));
        assertThat(enviar(envio, PNG).statusCode()).isEqualTo(201);

        abrir(claro, "/fin/t/" + gasto.id().substring(2));
        WebElement lista = claro.findElement(By.cssSelector("ul.lista[aria-label=Comprovantes]"));
        assertThat(claro.findElement(By.cssSelector(".legenda-lista")).getText()).isEqualTo("Comprovantes");
        WebElement item = lista.findElement(By.tagName("a"));
        assertThat(item.getText()).matches("Imagem PNG, enviada em \\d{2}/\\d{2}/\\d{4}");
        assertThat(lista.findElement(By.tagName("small")).getText()).startsWith("n-");
        String comprovante = lista.findElement(By.tagName("small")).getText();
        semViolacoes(claro, "lançamento com comprovante");

        item.click();
        esperarImagem(claro);

        abrir(claro, "/fin/n/" + comprovante.substring(2));
        claro.findElement(By.linkText("Abrir o arquivo")).click();
        esperarImagem(claro);
    }

    /** O navegador saiu da casca e desenhou o arquivo: a imagem de 1 x 1 pixel. */
    private static void esperarImagem(WebDriver navegador) {
        esperar(navegador).until(ExpectedConditions.urlContains("/fin/comprovante/"));
        esperar(navegador).until(d -> "image/png".equals(((JavascriptExecutor) d).executeScript("return document.contentType")));
        assertThat(((JavascriptExecutor) navegador).executeScript("return document.images[0] && document.images[0].naturalWidth")).isEqualTo(1L);
    }

    private static HttpResponse<String> enviar(String caminho, byte[] arquivo) throws Exception {
        String limite = "limite-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream corpo = new java.io.ByteArrayOutputStream();
        corpo.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"nota.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        corpo.write(arquivo);
        corpo.write(("\r\n--" + limite + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url(caminho)))
                .header("Content-Type", "multipart/form-data; boundary=" + limite)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void enderecoDeOutroDonoMostraNaoEncontrado() {
        abrir(claro, "/fin/contas");
        claro.get(url("/fin/t/ZZZZZZ"));
        WebElement aviso = esperar(claro).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[role=alert]")));

        assertThat(aviso.getText()).contains("Não encontramos");
    }

    @Test
    void textoDoDonoComHtmlNaoViraMarcacao() {
        abrir(claro, "/fin/contas");
        Object criados = ((JavascriptExecutor) claro).executeScript("""
                const alvo = document.createElement('div');
                AlicerceVista.renderizar(alvo, {catalogo: 'alicerce/v1', raiz: 'r',
                  componentes: [{id: 'r', componente: 'pagina', titulo: {caminho: '/x'}, filhos: ['t']}, {id: 't', componente: 'texto', valor: {caminho: '/x'}}],
                  dados: {x: '<img src=x onerror=alert(1)>'}}, {});
                return alvo.querySelectorAll('img').length""");

        assertThat(((Number) criados).intValue()).isZero();
    }

    @Test
    void oRenderizadorRecusaSozinhoComponenteForaDoCatalogo() {
        abrir(claro, "/fin/contas");
        Object recusou = ((JavascriptExecutor) claro).executeScript("""
                const alvo = document.createElement('div');
                try {
                  AlicerceVista.renderizar(alvo, {catalogo: 'alicerce/v1', raiz: 'r', dados: {},
                    componentes: [{id: 'r', componente: 'pagina', titulo: 'A', filhos: ['t']}, {id: 't', componente: 'iframe'}]}, {});
                  return false;
                } catch (e) { return alvo.childElementCount === 0; }""");

        assertThat(recusou).isEqualTo(true);
    }

    @Test
    void desfazerPelaTelaMostraAPreviaEAplicaSoComAConfirmacao() {
        var gasto = massa.gasto(ana.chamador(), "Gasto por engano");
        String caminho = gasto.operacao().url().substring(url("").length());

        percorrerDesfazer(claro, caminho, "claro");

        var operacao = banco.fetchOne("select estado, desfeita_por from operacao where id = ?", gasto.operacao().id());
        assertThat(operacao.get(0, String.class)).isEqualTo("desfeita");
        assertThat(banco.fetchOne("select cliente from operacao where id = ?", operacao.get(1, java.util.UUID.class)).get(0, String.class)).isEqualTo("app-web");

        claro.findElement(By.linkText("Gasto por engano")).click();
        esperar(claro).until(ExpectedConditions.textToBe(By.tagName("h1"), "Gasto por engano"));
        abrir(claro, caminho);
        assertThat(claro.findElement(By.id("conteudo")).getText()).contains("desfeita");
        assertThat(claro.findElements(By.xpath("//button[text()='Ver prévia do desfazer']"))).isEmpty();
    }

    @Test
    void recusaDoKeycloakAparecePeloMotivo() {
        abrir(claro, "/fin/contas");
        ((JavascriptExecutor) claro).executeScript("sessionStorage.setItem('login', JSON.stringify({estado: 'e', verificador: 'v', destino: '/fin/contas'}))");
        claro.get(url("/callback?error=invalid_scope&state=e"));

        WebElement aviso = esperar(claro).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[role=alert]")));

        assertThat(aviso.getText()).contains("invalid_scope");
    }

    @Test
    void cancelarNaPreviaNaoMudaNada() {
        var gasto = massa.gasto(ana.chamador(), "Gasto mantido");
        abrir(claro, gasto.operacao().url().substring(url("").length()));
        claro.findElement(By.xpath("//button[text()='Ver prévia do desfazer']")).click();
        esperar(claro).until(ExpectedConditions.textToBe(By.tagName("h1"), "Prévia do desfazer"));

        claro.findElement(By.xpath("//button[text()='Cancelar']")).click();

        esperar(claro).until(ExpectedConditions.textToBe(By.tagName("h1"), "Operação"));
        assertThat(banco.fetchOne("select estado from operacao where id = ?", gasto.operacao().id()).get(0, String.class)).isEqualTo("aplicada");
    }

    @Test
    void desfazerBloqueadoMostraAsDependentesSemViolarWcagAa() {
        var categoria = massa.categoriaComGasto(ana.chamador(), "Bloqueada");
        abrir(claro, categoria.operacao().url().substring(url("").length()));
        claro.findElement(By.xpath("//button[text()='Ver prévia do desfazer']")).click();

        WebElement aviso = esperar(claro).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[role=alert]")));

        assertThat(aviso.getText()).contains("dependem");
        assertThat(claro.findElements(By.cssSelector("#conteudo .lista a"))).isNotEmpty();
        assertThat(claro.findElement(By.linkText("Voltar para a operação")).isDisplayed()).isTrue();
        semViolacoes(claro, "desfazer bloqueado");
    }

    @Test
    void oFluxoDeDesfazerPassaNoTemaEscuroENaLarguraDeCelular() {
        ChromeOptions opcoes = opcoes().addArguments("--blink-settings=preferredColorScheme=0");
        WebDriver escuro = conectar(opcoes);
        try {
            entrar(escuro);
            percorrerDesfazer(escuro, massa.gasto(ana.chamador(), "Gasto escuro").operacao().url().substring(url("").length()), "escuro");
            escuro.manage().window().setSize(new Dimension(360, 740));
            percorrerDesfazer(escuro, massa.gasto(ana.chamador(), "Gasto no celular").operacao().url().substring(url("").length()), "celular");
        } finally {
            escuro.quit();
        }
    }

    @Test
    void oMcpAppNaoGanhaOBotaoNemNaTelaPadraoDaOperacao() throws Exception {
        String token = Tokens.daAna("fin:read");
        String pagina = rpc(token, "resources/read", "{\"uri\":\"ui://alicerce/vista\"}").get("contents").get(0).get("text").asString();
        JsonNode valido = rpc(token, "tools/call", """
                {"name":"mostrar_vista","arguments":{"endereco":"%s"}}""".formatted(ana.caminhos().get("operacao")));
        assertThat(valido.get("isError").asBoolean()).as(valido.toString()).isFalse();

        hospedar(pagina, valido.toString());

        assertThat(claro.findElement(By.tagName("h1")).getText()).isEqualTo("Operação");
        assertThat(claro.findElements(By.xpath("//button[contains(., 'esfazer')]"))).isEmpty();
        claro.switchTo().defaultContent();
    }

    @Test
    void enviarFotoPelaTelaDoLancamentoEVoltarAEle() throws Exception {
        var gasto = massa.gasto(ana.chamador(), "Almoço fotografado").valor();

        percorrerEnvio(claro, gasto.id(), "foto", "claro");

        assertThat(claro.findElement(By.cssSelector(".legenda-lista")).getText()).isEqualTo("Fotos");
        WebElement fotos = claro.findElement(By.cssSelector("ul.lista[aria-label=Fotos]"));
        assertThat(fotos.findElement(By.tagName("a")).getText()).matches("Imagem PNG, enviada em \\d{2}/\\d{2}/\\d{4}");
        assertThat(claro.findElements(By.cssSelector("ul.lista[aria-label=Comprovantes]"))).isEmpty();
        assertThat(banco.fetchOne("""
                select cliente from operacao where capacidade = 'enviar_comprovante' order by criada_em desc limit 1""").get(0, String.class))
                .isEqualTo("app-web");
        semViolacoes(claro, "lançamento com foto");
        massa.anular(ana.chamador(), gasto.id()); // fora dos totais que as outras telas conferem
    }

    @Test
    void oEnvioPassaNoTemaEscuroENaLarguraDeCelular() throws Exception {
        ChromeOptions opcoes = opcoes().addArguments("--blink-settings=preferredColorScheme=0");
        WebDriver escuro = conectar(opcoes);
        try {
            entrar(escuro);
            String nota = massa.gasto(ana.chamador(), "Nota no escuro").valor().id();
            percorrerEnvio(escuro, nota, "comprovante", "escuro");
            assertThat(escuro.findElement(By.cssSelector("ul.lista[aria-label=Comprovantes]")).isDisplayed()).isTrue();
            massa.anular(ana.chamador(), nota); // fora dos totais que as outras telas conferem
            escuro.manage().window().setSize(new Dimension(360, 740));
            String foto = massa.gasto(ana.chamador(), "Foto no celular").valor().id();
            percorrerEnvio(escuro, foto, "foto", "celular");
            massa.anular(ana.chamador(), foto);
        } finally {
            escuro.quit();
        }
    }

    @Test
    void oBotaoDeEnvioSoApareceEmLancamentoVigenteENuncaNoMcpApp() throws Exception {
        var gasto = massa.gasto(ana.chamador(), "Anulado na tela").valor();
        massa.anular(ana.chamador(), gasto.id());
        abrir(claro, "/fin/t/" + gasto.id().substring(2));
        assertThat(claro.findElement(By.id("conteudo")).getText()).contains("anulado");
        assertThat(claro.findElements(By.xpath("//button[text()='Enviar comprovante ou foto']"))).isEmpty();

        String token = Tokens.daAna("fin:read");
        String pagina = rpc(token, "resources/read", "{\"uri\":\"ui://alicerce/vista\"}").get("contents").get(0).get("text").asString();
        JsonNode valido = rpc(token, "tools/call", """
                {"name":"mostrar_vista","arguments":{"endereco":"%s"}}""".formatted(ana.caminhos().get("lancamento")));
        hospedar(pagina, valido.toString());
        assertThat(claro.findElement(By.tagName("h1")).getText()).isEqualTo("Feira da semana");
        assertThat(claro.findElements(By.xpath("//button[contains(., 'Enviar')]"))).isEmpty();
        claro.switchTo().defaultContent();
    }

    /** Da tela do lançamento à página de envio e de volta, com o verificador em cada passo e nada estourando a página. */
    private void percorrerEnvio(WebDriver navegador, String lancamento, String natureza, String tema) throws IOException {
        abrir(navegador, "/fin/t/" + lancamento.substring(2));
        semViolacoes(navegador, tema + " lançamento");
        navegador.findElement(By.xpath("//button[text()='Enviar comprovante ou foto']")).click();
        esperar(navegador).until(ExpectedConditions.urlContains("/fin/anexo/"));
        esperar(navegador).until(ExpectedConditions.textToBe(By.tagName("h1"), "Enviar comprovante ou foto"));
        assertThat(navegador.findElement(By.cssSelector("input[value=comprovante]")).isSelected()).isTrue();
        semViolacoes(navegador, tema + " página de envio");
        semSobra(navegador, tema + " página de envio");

        java.nio.file.Path arquivo = java.nio.file.Files.createTempFile("envio", ".png");
        java.nio.file.Files.write(arquivo, PNG);
        ((RemoteWebDriver) navegador).setFileDetector(new org.openqa.selenium.remote.LocalFileDetector());
        navegador.findElement(By.cssSelector("input[value=" + natureza + "]")).click();
        navegador.findElement(By.id("arquivo")).sendKeys(arquivo.toString());
        navegador.findElement(By.cssSelector("button[type=submit]")).click();
        esperar(navegador).until(ExpectedConditions.textToBe(By.tagName("h1"), "foto".equals(natureza) ? "Foto recebida" : "Comprovante recebido"));
        semViolacoes(navegador, tema + " envio recebido");
        semSobra(navegador, tema + " envio recebido");

        navegador.findElement(By.linkText("Voltar ao lançamento")).click();
        esperarTela(navegador);
        assertThat(navegador.getCurrentUrl()).isEqualTo(url("/fin/t/" + lancamento.substring(2)));
    }

    /** Da tela da operação ao resultado, com o verificador em cada passo e nada estourando a página. */
    private void percorrerDesfazer(WebDriver navegador, String caminho, String tema) {
        abrir(navegador, caminho);
        semViolacoes(navegador, tema + " operação");
        navegador.findElement(By.xpath("//button[text()='Ver prévia do desfazer']")).click();
        esperar(navegador).until(ExpectedConditions.textToBe(By.tagName("h1"), "Prévia do desfazer"));
        assertThat(navegador.findElement(By.id("conteudo")).getText()).contains("Nada mudou ainda");
        semViolacoes(navegador, tema + " prévia");
        semSobra(navegador, tema + " prévia");

        navegador.findElement(By.xpath("//button[text()='Confirmar desfazer']")).click();
        esperar(navegador).until(ExpectedConditions.textToBe(By.tagName("h1"), "Operação desfeita"));
        semViolacoes(navegador, tema + " resultado");
        semSobra(navegador, tema + " resultado");
    }

    /** As palavras das células e dos rótulos que o navegador partiu em mais de uma linha. */
    private static final String PALAVRAS_QUEBRADAS = """
            const quebradas = [];
            for (const celula of document.querySelectorAll('th, td, caption')) {
              const andar = document.createTreeWalker(celula, NodeFilter.SHOW_TEXT);
              for (let no = andar.nextNode(); no; no = andar.nextNode()) {
                const texto = no.textContent;
                const palavra = /\\S+/g;
                for (let m = palavra.exec(texto); m; m = palavra.exec(texto)) {
                  const faixa = document.createRange();
                  faixa.setStart(no, m.index);
                  faixa.setEnd(no, m.index + m[0].length);
                  const topos = new Set([...faixa.getClientRects()].filter(r => r.width > 0).map(r => Math.round(r.top)));
                  if (topos.size > 1) quebradas.push(m[0]);
                }
              }
            }
            return quebradas;""";

    /** No cartão, o título da coluna começa na borda esquerda, também nas colunas de número. */
    private static final String ROTULOS_DESALINHADOS = """
            return [...document.querySelectorAll('td > .rotulo')].filter(r => r.getClientRects().length > 0)
              .filter(r => Math.abs(r.getBoundingClientRect().left - r.parentElement.getBoundingClientRect().left) > 1)
              .map(r => r.textContent);""";

    private static final String CABECALHO_NUMA_LINHA = """
            const itens = [...document.querySelectorAll('.topo nav > a, .topo nav > button')];
            const topo = itens.map(i => i.getBoundingClientRect());
            return topo.every(r => Math.abs((r.top + r.height / 2) - (topo[0].top + topo[0].height / 2)) < 4);""";

    @SuppressWarnings("unchecked")
    private static java.util.List<String> palavrasQuebradas(WebDriver navegador) {
        return (java.util.List<String>) ((JavascriptExecutor) navegador).executeScript(PALAVRAS_QUEBRADAS);
    }

    private static void semSobra(WebDriver navegador, String tela) {
        Object sobra = ((JavascriptExecutor) navegador).executeScript("return document.documentElement.scrollWidth - document.documentElement.clientWidth");
        assertThat(((Number) sobra).intValue()).as(tela).isLessThanOrEqualTo(0);
    }

    @Test
    void aPrestacaoDeContasAbreSemLoginNosDoisTemasENoCelular() {
        String url = prestarContas.executar(ana.chamador(), new tools.alicerce.financeiro.aplicacao.PrestarContas.Entrada(null, "Pessoal", null, null,
                "2026-10", true, null, null)).valor().urlPublica();
        for (String tema : List.of("claro", "escuro")) {
            ChromeOptions opcoes = tema.equals("escuro") ? opcoes().addArguments("--blink-settings=preferredColorScheme=0") : opcoes();
            WebDriver anonimo = conectar(opcoes);
            try {
                anonimo.get(url);
                WebElement titulo = esperarTela(anonimo);
                assertThat(titulo.getText()).isEqualTo("Prestação de contas");
                assertThat(anonimo.getTitle()).isEqualTo("Prestação de contas");
                assertThat(anonimo.getCurrentUrl()).isEqualTo(url);
                assertThat(anonimo.findElements(By.id("username"))).isEmpty();
                String texto = anonimo.findElement(By.id("conteudo")).getText().replaceAll("\\p{Z}", " ");
                assertThat(texto).contains("R$ 183,45", "Feira da semana", "Sacolão", "Pix", "Crédito", "Pessoal")
                        .doesNotContain("Itaú", "Nubank", "Wise", "R$ 1.000");
                for (String componente : List.of("totais", "grafico", "tabela", "lista")) {
                    assertThat(anonimo.findElements(By.cssSelector("[data-componente=" + componente + "]"))).as(tema + " " + componente).isNotEmpty();
                }
                assertThat(anonimo.findElement(By.cssSelector("meta[name=robots]")).getDomAttribute("content")).contains("noindex");
                semViolacoes(anonimo, tema + " prestação de contas");

                anonimo.manage().window().setSize(new Dimension(360, 740));
                anonimo.navigate().refresh();
                esperarTela(anonimo);
                semSobra(anonimo, tema + " prestação de contas em 360 px");
                assertThat(palavrasQuebradas(anonimo)).as(tema + " 360 px").isEmpty();
                semViolacoes(anonimo, tema + " prestação de contas em 360 px");
            } finally {
                anonimo.quit();
            }
        }

        prestarContas.executar(ana.chamador(), new tools.alicerce.financeiro.aplicacao.PrestarContas.Entrada(null, null, null, null, null, null, null, url));
        WebDriver anonimo = navegador();
        try {
            anonimo.get(url);
            assertThat(anonimo.findElement(By.tagName("h1")).getText()).isEqualTo("Link indisponível");
            assertThat(anonimo.findElement(By.id("conteudo")).getText()).doesNotContain("R$");
            semViolacoes(anonimo, "prestação revogada");
        } finally {
            anonimo.quit();
        }
    }

    @Test
    void oMcpAppDesenhaAVistaNumHospedeiroIsolado() throws Exception {
        String token = Tokens.daAna("fin:read");
        String pagina = rpc(token, "resources/read", "{\"uri\":\"ui://alicerce/vista\"}").get("contents").get(0).get("text").asString();
        JsonNode valido = rpc(token, "tools/call", """
                {"name":"mostrar_vista","arguments":{"endereco":"%s"}}""".formatted(ana.caminhos().get("lancamento")));
        assertThat(valido.get("isError").asBoolean()).as(valido.toString()).isFalse();

        hospedar(pagina, valido.toString());
        assertThat(claro.findElement(By.cssSelector("[data-componente=tabela]")).isDisplayed()).isTrue();
        assertThat(claro.findElement(By.tagName("h1")).getText()).isEqualTo("Feira da semana");
        claro.switchTo().defaultContent();

        // O quadro do assistente é estreito: a tabela vira cartões também ali, sem partir palavra.
        JsonNode lista = rpc(token, "tools/call", """
                {"name":"mostrar_vista","arguments":{"endereco":"%s"}}""".formatted(ana.caminhos().get("lancamentos")));
        hospedar(pagina, lista.toString());
        assertThat(palavrasQuebradas(claro)).as("MCP App").isEmpty();
        claro.switchTo().defaultContent();

        hospedar(pagina, "{\"structuredContent\":{\"catalogo\":\"alicerce/v1\",\"raiz\":\"r\",\"dados\":{},\"componentes\":[{\"id\":\"r\",\"componente\":\"iframe\"}]}}");
        assertThat(claro.findElement(By.className("aviso")).getText()).contains("não pôde ser desenhada");
        assertThat(claro.findElements(By.tagName("h1"))).isEmpty();
        claro.switchTo().defaultContent();
    }

    /** Faz o papel do Claude ou do ChatGPT: iframe só com scripts, o aperto de mão e o resultado da tool. */
    private void hospedar(String pagina, String resultado) {
        claro.get("about:blank");
        ((JavascriptExecutor) claro).executeScript("""
                const [pagina, resultado] = arguments;
                const quadro = document.createElement('iframe');
                quadro.sandbox = 'allow-scripts';
                addEventListener('message', (e) => {
                  const m = e.data;
                  if (m.method === 'ui/initialize') {
                    quadro.contentWindow.postMessage({jsonrpc: '2.0', id: m.id,
                      result: {protocolVersion: '2026-01-26', hostInfo: {name: 'teste'}, hostCapabilities: {}}}, '*');
                  } else if (m.method === 'ui/notifications/initialized') {
                    quadro.contentWindow.postMessage({jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: JSON.parse(resultado)}, '*');
                  }
                });
                quadro.srcdoc = pagina;
                document.body.append(quadro);""", pagina, resultado);
        claro.switchTo().frame(0);
        esperar(claro).until(d -> !d.findElements(By.cssSelector("[data-componente=pagina], .aviso")).isEmpty());
    }

    private void semViolacoes(WebDriver navegador, String tela) {
        Results resultado = new AxeBuilder().withTags(REGRAS).analyze(navegador);
        assertThat(resultado.getViolations()).as(tela + ": " + resultado.getViolations().stream()
                .map(v -> v.getId() + " " + v.getNodes().stream().map(n -> n.getHtml()).limit(2).toList()).toList()).isEmpty();
    }

    private void abrir(WebDriver navegador, String caminho) {
        navegador.get(url(caminho));
        esperarTela(navegador);
    }

    private WebElement esperarTela(WebDriver navegador) {
        return esperar(navegador).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-componente=pagina] h1")));
    }

    private void entrar(WebDriver navegador) {
        navegador.get(url("/fin/contas"));
        esperar(navegador).until(ExpectedConditions.presenceOfElementLocated(By.id("username")));
        preencherLogin(navegador);
        esperarTela(navegador);
    }

    private static void preencherLogin(WebDriver navegador) {
        navegador.findElement(By.id("username")).sendKeys("ana");
        navegador.findElement(By.id("password")).sendKeys(KeycloakDeTeste.SENHA);
        navegador.findElement(By.id("kc-login")).click();
    }

    private static WebDriverWait esperar(WebDriver navegador) {
        return new WebDriverWait(navegador, Duration.ofSeconds(30));
    }

    private static ChromeOptions opcoes() {
        return new ChromeOptions().addArguments("--headless=new", "--window-size=1280,900");
    }

    private static WebDriver navegador() {
        return conectar(opcoes());
    }

    private static WebDriver conectar(ChromeOptions opcoes) {
        try {
            return new RemoteWebDriver(URI.create("http://localhost:" + PORTA_NAVEGADOR).toURL(), opcoes);
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String url(String caminho) {
        return "http://localhost:" + PORTA_APP + caminho;
    }

    private static HttpRequest.Builder pedido(String caminho, String token) {
        return HttpRequest.newBuilder(URI.create(url(caminho))).header("Authorization", "Bearer " + token);
    }

    private JsonNode rpc(String token, String metodo, String parametros) throws Exception {
        HttpRequest requisicao = pedido("/mcp", token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + metodo + "\",\"params\":" + parametros + "}"))
                .build();
        String corpo = HttpClient.newHttpClient().send(requisicao, HttpResponse.BodyHandlers.ofString()).body();
        return JsonMapper.builder().build().readTree(corpo).get("result");
    }
}
