package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.Vistas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Testes de aceite: a trilha de auditoria na interface, pela API das vistas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class, DadosDaInterface.class})
class TrilhaAuditoriaTest {

    private static final DateTimeFormatter MINUTO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort int porta;
    @Autowired DadosDaInterface massa;
    @Autowired Vistas vistas;
    @Autowired CorrigirLancamento corrigir;
    @Autowired DSLContext banco;

    @Test
    void aListaJuntaOperacoesERecusadasComAHoraNoFusoDoDonoEOAgentePeloNome() {
        DadosDaInterface.Enderecos ana = massa.preparar(massa.novoDono());

        JsonNode vista = abrir(ana.chamador(), "/fin/operacoes", null, null, null, null, null);
        JsonNode eventos = vista.get("dados").get("eventos");

        List<JsonNode> recusadas = new ArrayList<>();
        eventos.forEach(e -> {
            assertThat(e.get("horario").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");
            assertThat(e.get("agente").asString()).isEqualTo("Claude");
            if (e.get("recusada").asBoolean()) {
                recusadas.add(e);
            }
        });
        assertThat(recusadas).hasSize(1);
        JsonNode recusa = recusadas.get(0);
        assertThat(recusa.get("estado").asString()).isEqualTo("Recusada");
        assertThat(recusa.get("descricao").asString()).isEqualTo("tentativa de registrar gasto: faltaram campos obrigatórios");
        assertThat(recusa.has("url")).as("a recusa não tem tela").isFalse();
        assertThat(tools.alicerce.financeiro.aplicacao.TrilhaAuditoria.agente("pagina-envio")).isEqualTo("Página de envio de comprovante");
        assertThat(tools.alicerce.financeiro.aplicacao.TrilhaAuditoria.agente("outro-cliente")).isEqualTo("outro-cliente");

        // A hora é a de São Paulo, o fuso do dono, e não a UTC gravada.
        String codigo = ana.caminhos().get("operacao").substring("/fin/o/".length());
        OffsetDateTime criada = banco.fetchOne("""
                select o.criada_em from operacao o join referencia_curta r on r.entidade_id = o.id where r.codigo = ?""", "o-" + codigo)
                .get(0, OffsetDateTime.class);
        String esperado = criada.atZoneSameInstant(ZoneId.of("America/Sao_Paulo")).format(MINUTO);
        JsonNode gasto = null;
        for (JsonNode e : eventos) {
            if (e.path("url").asString("").endsWith(ana.caminhos().get("operacao"))) {
                gasto = e;
            }
        }
        assertThat(gasto).isNotNull();
        assertThat(gasto.get("horario").asString()).isEqualTo(esperado);
        assertThat(gasto.get("estado").asString()).isEqualTo("Aplicada");

        // As leituras com retrato ficam ocultas por padrão.
        assertThat(eventos.toString()).doesNotContain("relatório ", "ritmo de gastos ");

        JsonNode tabela = componente(vista, "tabela");
        assertThat(tabela.get("campo_destaque").asString()).isEqualTo("recusada");
        assertThat(tabela.toString()).contains("data_hora");
        assertThat(vista.get("dados").get("filtros").toString()).contains("Só Claude", "Últimos 7 dias", "Incluir relatórios")
                .doesNotContain("Todos os eventos");
    }

    @Test
    void osFiltrosDaUrlSeparamAgentePeriodoERelatorios() throws Exception {
        DadosDaInterface.Enderecos ana = massa.preparar(massa.novoDono());
        Chamador pelaOpenAi = new Chamador(ana.chamador().dono(), "chatgpt", ana.chamador().escopos());
        massa.gasto(pelaOpenAi, "Pelo ChatGPT");
        massa.gastoRecusado(pelaOpenAi);

        JsonNode soChatgpt = abrir(ana.chamador(), "/fin/operacoes", "chatgpt", null, null, null, null);
        JsonNode eventos = soChatgpt.get("dados").get("eventos");
        assertThat(eventos).hasSize(2);
        eventos.forEach(e -> assertThat(e.get("agente").asString()).isEqualTo("ChatGPT"));
        assertThat(soChatgpt.get("dados").get("filtro").get("agente").asString()).isEqualTo("ChatGPT");
        assertThat(soChatgpt.get("dados").get("filtros").toString()).contains("Só Claude", "Todos os eventos").doesNotContain("Só ChatGPT");

        JsonNode comRelatorios = abrir(ana.chamador(), "/fin/operacoes", null, null, null, "true", null);
        assertThat(comRelatorios.get("dados").get("eventos").toString()).contains("relatório ", "ritmo de gastos ");
        assertThat(comRelatorios.get("dados").get("filtros").toString()).contains("Ocultar relatórios");

        JsonNode noPassado = abrir(ana.chamador(), "/fin/operacoes", null, "2000-01-01", "2000-01-02", null, null);
        assertThat(noPassado.get("dados").get("eventos")).isEmpty();
        assertThat(noPassado.get("dados").get("filtro").get("de").asString()).isEqualTo("2000-01-01");

        // Pelo HTTP, os parâmetros chegam à tela como na URL da interface.
        String token = Tokens.doGui("fin:read");
        HttpResponse<String> resposta = get("/api/fin/vista?caminho=" + URLEncoder.encode("/fin/operacoes", StandardCharsets.UTF_8)
                + "&cliente=chatgpt&incluir_relatorios=true&de=2000-01-01", token);
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        JsonNode filtro = json.readTree(resposta.body()).get("dados").get("filtro");
        assertThat(filtro.get("agente").asString()).isEqualTo("ChatGPT");
        assertThat(filtro.get("relatorios").asString()).isEqualTo("incluídos");
        assertThat(filtro.get("de").asString()).isEqualTo("2000-01-01");
    }

    @Test
    void aPaginacaoJuntaAsDuasFontesSemPerderNemRepetir() {
        DadosDaInterface.Enderecos ana = massa.preparar(massa.novoDono());
        for (int i = 0; i < 20; i++) {
            massa.gasto(ana.chamador(), "Gasto " + i);
            massa.gastoRecusado(ana.chamador());
        }
        UUID dono = ana.chamador().dono();
        int operacoes = banco.fetchCount(banco.selectFrom("operacao").where("dono_id = ? and capacidade not in ('resumo_periodo', 'ritmo_gastos', 'cortes_necessarios', 'projetar')", dono));
        int recusas = banco.fetchCount(banco.selectFrom("evento_auditoria").where("dono_id = ? and resultado = 'erro'", dono));
        assertThat(recusas).isEqualTo(21);

        List<JsonNode> todos = new ArrayList<>();
        String cursor = null;
        int paginas = 0;
        do {
            JsonNode dados = abrir(ana.chamador(), "/fin/operacoes", null, null, null, null, cursor).get("dados");
            dados.get("eventos").forEach(todos::add);
            cursor = dados.has("proxima") ? dados.get("proxima").asString().replaceAll(".*[?&]cursor=([^&]*).*", "$1") : null;
            paginas++;
        } while (cursor != null && paginas < 10);

        assertThat(paginas).isGreaterThan(1);
        assertThat(todos).hasSize(operacoes + recusas);
        Set<String> urls = new HashSet<>();
        int recusadas = 0;
        for (JsonNode e : todos) {
            if (e.get("recusada").asBoolean()) {
                recusadas++;
            } else {
                assertThat(urls.add(e.get("url").asString())).as("operação repetida").isTrue();
            }
        }
        assertThat(recusadas).isEqualTo(recusas);
        for (int i = 1; i < todos.size(); i++) {
            assertThat(todos.get(i - 1).get("horario").asString()).isGreaterThanOrEqualTo(todos.get(i).get("horario").asString());
        }
    }

    @Test
    void daOperacaoSeChegaAoRegistroEDoRegistroAOperacao() {
        DadosDaInterface.Enderecos ana = massa.preparar(massa.novoDono());
        Chamador chamador = ana.chamador();

        JsonNode operacao = abrir(chamador, ana.caminhos().get("operacao"), null, null, null, null, null).get("dados").get("operacao");
        assertThat(operacao.get("agente").asString()).isEqualTo("Claude");
        assertThat(operacao.get("cliente").asString()).isEqualTo("claude-ai");
        assertThat(operacao.get("horario_local").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");
        JsonNode afetado = operacao.get("afetados").get(0);
        assertThat(afetado.get("acao").asString()).isEqualTo("criado");
        assertThat(afetado.get("registro").asString()).isEqualTo("lançamento");
        assertThat(afetado.get("url").asString()).endsWith(ana.caminhos().get("lancamento"));

        JsonNode lancamento = abrir(chamador, ana.caminhos().get("lancamento"), null, null, null, null, null).get("dados").get("lancamento");
        assertThat(lancamento.get("operacao").get("url").asString()).endsWith(ana.caminhos().get("operacao"));
        assertThat(lancamento.has("alterou_url")).isFalse();

        JsonNode conta = abrir(chamador, ana.caminhos().get("conta"), null, null, null, null, null).get("dados").get("conta");
        assertThat(conta.get("criou_url").asString()).contains("/fin/o/");
        JsonNode compromisso = abrir(chamador, ana.caminhos().get("compromisso"), null, null, null, null, null).get("dados").get("compromisso");
        assertThat(compromisso.get("criou_url").asString()).contains("/fin/o/");

        // Anular um lançamento: a anulação aparece como a última operação que o alterou, e aponta para ele.
        var gasto = massa.gasto(chamador, "Gasto anulado");
        var anulacao = corrigir.executar(chamador, new CorrigirLancamento.Entrada(UUID.randomUUID().toString(), null, gasto.valor().id(), "anular",
                null, "lançado por engano", null));
        String caminhoGasto = "/fin/t/" + gasto.valor().id().substring(2);
        JsonNode anulado = abrir(chamador, caminhoGasto, null, null, null, null, null).get("dados").get("lancamento");
        assertThat(anulado.get("alterou_url").asString()).isEqualTo(anulacao.operacao().url());

        String caminhoAnulacao = "/fin/o/" + anulacao.operacao().referencia().codigo();
        JsonNode afetados = abrir(chamador, caminhoAnulacao, null, null, null, null, null).get("dados").get("operacao").get("afetados");
        boolean estornado = false;
        for (JsonNode a : afetados) {
            if (a.get("acao").asString().equals("estornado")) {
                estornado = true;
                assertThat(a.get("url").asString()).endsWith(caminhoGasto);
            }
        }
        assertThat(estornado).as(afetados.toString()).isTrue();
    }

    private JsonNode abrir(Chamador chamador, String caminho, String cliente, String de, String ate, String relatorios, String cursor) {
        return json.valueToTree(vistas.abrir(chamador, new Vistas.Pedido(caminho, cursor, de, ate, null, cliente, relatorios)));
    }

    private static JsonNode componente(JsonNode vista, String tipo) {
        for (JsonNode c : vista.get("componentes")) {
            if (c.get("componente").asString().equals(tipo)) {
                return c;
            }
        }
        throw new AssertionError("Sem " + tipo);
    }

    private HttpResponse<String> get(String caminho, String token) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho)).header("Authorization", "Bearer " + token).build();
        return http.send(pedido, HttpResponse.BodyHandlers.ofString());
    }
}
