package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.OperacaoRegistrada;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Testes de aceite pela API: tokens reais do cliente app-web, resultado conferido no banco. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class, DadosDaInterface.class})
class DesfazerInterfaceTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort int porta;
    @Autowired DadosDaInterface massa;
    @Autowired Desfazer desfazer;
    @Autowired DSLContext banco;

    private static Chamador bia;
    private static Chamador outro;
    private String tokenDesfazer;

    @BeforeEach
    void preparar() throws Exception {
        tokenDesfazer = Tokens.daInterface("bia", "fin:undo");
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
        assertThat(banco.fetch("""
                select l.id from lancamento l join movimento m on m.lancamento_id = l.id
                where l.dono_id = ? group by l.id having sum(m.valor_centavos) <> 0""", bia.dono())).isEmpty();
    }

    @Test
    void aPreviaNaoMudaNadaEAConfirmacaoEstornaComClienteEAuditoria() throws Exception {
        OperacaoRegistrada operacao = massa.gasto(bia, "Por engano").operacao();
        long lancamentos = contar("select count(*) from lancamento where dono_id = ?", bia.dono());

        HttpResponse<String> resposta = previa(operacao, tokenDesfazer);

        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode previa = json.readTree(resposta.body());
        assertThat(previa.get("vista").get("catalogo").asString()).isEqualTo("alicerce/v1");
        assertThat(previa.get("vista").get("dados").get("estornados").toString()).contains("Por engano", "10.00");
        assertThat(previa.get("vista").toString()).doesNotContain(previa.get("confirmacao").asString(), "proximo");
        assertThat(previa.get("valida_ate").asString()).isNotBlank();
        assertThat(estado(operacao)).isEqualTo("aplicada");
        assertThat(contar("select count(*) from lancamento where dono_id = ?", bia.dono())).isEqualTo(lancamentos);

        HttpResponse<String> aplicada = aplicar(operacao, tokenDesfazer, previa.get("confirmacao").asString(), UUID.randomUUID().toString());

        assertThat(aplicada.statusCode()).as(aplicada.body()).isEqualTo(200);
        assertThat(json.readTree(aplicada.body()).get("vista").get("dados").get("registro").get("id").asString()).startsWith("o-");
        assertThat(estado(operacao)).isEqualTo("desfeita");
        UUID desfez = desfeitaPor(operacao);
        assertThat(banco.fetchOne("select cliente from operacao where id = ?", desfez).get(0, String.class)).isEqualTo("app-web");
        assertThat(contar("select count(*) from lancamento where dono_id = ?", bia.dono())).isEqualTo(lancamentos + 1);
        var evento = banco.fetchOne("select cliente, escopos::text, resultado from evento_auditoria where operacao_id = ?", desfez);
        assertThat(evento.get(0, String.class)).isEqualTo("app-web");
        assertThat(evento.get(1, String.class)).contains("fin:undo").doesNotContain("fin:write");
        assertThat(evento.get(2, String.class)).isEqualTo("sucesso");
    }

    @Test
    void oResultadoEIgualAoDoDesfazerPeloAssistente() throws Exception {
        OperacaoRegistrada peloAssistente = massa.gasto(bia, "Pelo assistente").operacao();
        OperacaoRegistrada pelaTela = massa.gasto(bia, "Pela tela").operacao();
        Chamador assistente = new Chamador(bia.dono(), "claude-ai", bia.escopos());
        String alvo = peloAssistente.referencia().id();
        String confirmacao = desfazer.executar(assistente, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        desfazer.executar(assistente, new Desfazer.Entrada(UUID.randomUUID().toString(), alvo, null, confirmacao));

        desfazerPelaApi(pelaTela);

        assertThat(efeitos(desfeitaPor(pelaTela))).isNotEmpty().isEqualTo(efeitos(desfeitaPor(peloAssistente)));
    }

    @Test
    void tokenSemFinUndoNaoDesfaz() throws Exception {
        OperacaoRegistrada operacao = massa.gasto(bia, "Protegido").operacao();
        String confirmacao = json.readTree(previa(operacao, tokenDesfazer).body()).get("confirmacao").asString();

        assertThat(previa(operacao, null).statusCode()).isEqualTo(401);
        assertThat(aplicar(operacao, null, confirmacao, "a").statusCode()).isEqualTo(401);
        for (String token : List.of(Tokens.daInterface("bia", "fin:read"), Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure"))) {
            assertThat(previa(operacao, token).statusCode()).isEqualTo(403);
            assertThat(aplicar(operacao, token, confirmacao, UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
        }
        assertThat(estado(operacao)).isEqualTo("aplicada");
    }

    @Test
    void oTokenDeDesfazerNaoFazNenhumaOutraEscritaNemLe() throws Exception {
        HttpResponse<String> leitura = enviar(HttpRequest.newBuilder(uri("/api/fin/vista?caminho=/fin/contas"))
                .header("Authorization", "Bearer " + tokenDesfazer).GET());
        assertThat(leitura.statusCode()).isEqualTo(403);

        String comLeitura = Tokens.daInterface("bia", "fin:read fin:undo");
        for (String ferramenta : List.of("registrar_gasto", "registrar_ganho", "registrar_transferencia", "corrigir_lancamento", "anexar_comprovante",
                "definir_conta", "definir_meio_pagamento", "definir_ambito")) {
            assertThat(ferramenta(tokenDesfazer, ferramenta).statusCode()).as(ferramenta).isEqualTo(403);
            assertThat(ferramenta(comLeitura, ferramenta).statusCode()).as(ferramenta).isEqualTo(403);
        }
        // A tool do assistente exige fin:read: o token do clique não chega nem a ela.
        assertThat(ferramenta(tokenDesfazer, "desfazer").statusCode()).isEqualTo(403);
    }

    @Test
    void soAInterfaceConsegueOEscopo() {
        assertThatThrownBy(() -> Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:undo"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid_scope");
    }

    @Test
    void operacaoDeOutroDonoRespondeNaoEncontrado() throws Exception {
        OperacaoRegistrada alheia = massa.gasto(outro, "De outra conta").operacao();

        HttpResponse<String> resposta = previa(alheia, tokenDesfazer);

        assertThat(resposta.statusCode()).isEqualTo(404);
        assertThat(resposta.body()).doesNotContain("De outra conta");
        // Com uma confirmação legítima da própria conta, a de outro dono continua sem existir.
        String propria = json.readTree(previa(massa.gasto(bia, "Da própria conta").operacao(), tokenDesfazer).body()).get("confirmacao").asString();
        assertThat(aplicar(alheia, tokenDesfazer, propria, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        assertThat(previa("ZZZZZZ", tokenDesfazer).statusCode()).isEqualTo(404);
        assertThat(estado(alheia)).isEqualTo("aplicada");
    }

    @Test
    void semConfirmacaoNaoAplicaEConfirmacaoDeOutroAlvoERecusada() throws Exception {
        OperacaoRegistrada operacao = massa.gasto(bia, "Com prévia").operacao();
        OperacaoRegistrada vizinha = massa.gasto(bia, "Vizinha").operacao();
        String daVizinha = json.readTree(previa(vizinha, tokenDesfazer).body()).get("confirmacao").asString();

        assertThat(aplicar(operacao, tokenDesfazer, "", UUID.randomUUID().toString()).statusCode()).isEqualTo(422);
        HttpResponse<String> errada = aplicar(operacao, tokenDesfazer, daVizinha, UUID.randomUUID().toString());

        assertThat(errada.statusCode()).isEqualTo(409);
        assertThat(errada.body()).contains("confirmacao_invalida");
        assertThat(estado(operacao)).isEqualTo("aplicada");
        assertThat(estado(vizinha)).isEqualTo("aplicada");
    }

    @Test
    void repetirAMesmaConfirmacaoNaoAplicaDuasVezes() throws Exception {
        OperacaoRegistrada operacao = massa.gasto(bia, "Clique duplo").operacao();
        String confirmacao = json.readTree(previa(operacao, tokenDesfazer).body()).get("confirmacao").asString();
        String chave = UUID.randomUUID().toString();

        HttpResponse<String> primeira = aplicar(operacao, tokenDesfazer, confirmacao, chave);
        UUID desfez = desfeitaPor(operacao);
        HttpResponse<String> segunda = aplicar(operacao, tokenDesfazer, confirmacao, chave);

        assertThat(primeira.statusCode()).isEqualTo(200);
        assertThat(segunda.statusCode()).as(segunda.body()).isEqualTo(200);
        assertThat(json.readTree(segunda.body()).get("vista").get("dados").get("registro"))
                .isEqualTo(json.readTree(primeira.body()).get("vista").get("dados").get("registro"));
        assertThat(desfeitaPor(operacao)).isEqualTo(desfez);
    }

    @Test
    void desfazDeNovoERefazEBloqueiaComAsDependentes() throws Exception {
        OperacaoRegistrada gasto = massa.gasto(bia, "Refazer").operacao();
        desfazerPelaApi(gasto);

        HttpResponse<String> deNovo = previa(gasto, tokenDesfazer);
        assertThat(deNovo.statusCode()).isEqualTo(409);
        assertThat(deNovo.body()).contains("ja_desfeita");

        String registro = codigoDe(desfeitaPor(gasto));
        assertThat(previa(registro, tokenDesfazer).statusCode()).isEqualTo(200);

        OperacaoRegistrada estrutura = massa.categoria(bia, "Só estrutura").operacao();
        desfazerPelaApi(estrutura);
        assertThat(estado(estrutura)).isEqualTo("desfeita");

        HttpResponse<String> bloqueado = previa(massa.categoriaComGasto(bia, "Com dependente").operacao(), tokenDesfazer);
        assertThat(bloqueado.statusCode()).isEqualTo(409);
        JsonNode erro = json.readTree(bloqueado.body());
        assertThat(erro.get("codigo").asString()).isEqualTo("desfazer_bloqueado");
        assertThat(erro.get("candidatos").get(0).get("url").asString()).contains("/fin/o/");
    }

    private void desfazerPelaApi(OperacaoRegistrada operacao) throws Exception {
        HttpResponse<String> resposta = previa(operacao, tokenDesfazer);
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        String confirmacao = json.readTree(resposta.body()).get("confirmacao").asString();
        HttpResponse<String> aplicada = aplicar(operacao, tokenDesfazer, confirmacao, UUID.randomUUID().toString());
        assertThat(aplicada.statusCode()).as(aplicada.body()).isEqualTo(200);
    }

    private HttpResponse<String> previa(OperacaoRegistrada operacao, String token) throws Exception {
        return previa(operacao.referencia().codigo(), token);
    }

    private HttpResponse<String> previa(String codigo, String token) throws Exception {
        return post("/api/fin/operacoes/" + codigo + "/desfazer/previa", token, "{}");
    }

    private HttpResponse<String> aplicar(OperacaoRegistrada operacao, String token, String confirmacao, String chave) throws Exception {
        return post("/api/fin/operacoes/" + operacao.referencia().codigo() + "/desfazer", token,
                json.writeValueAsString(Map.of("confirmacao", confirmacao, "chave_idempotencia", chave)));
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

    private String estado(OperacaoRegistrada operacao) {
        return banco.fetchOne("select estado from operacao where id = ?", operacao.id()).get(0, String.class);
    }

    private UUID desfeitaPor(OperacaoRegistrada operacao) {
        return banco.fetchOne("select desfeita_por from operacao where id = ?", operacao.id()).get(0, UUID.class);
    }

    private List<String> efeitos(UUID operacao) {
        return banco.fetch("select tipo from efeito_operacao where operacao_id = ? order by ordem", operacao).getValues(0, String.class);
    }

    private String codigoDe(UUID operacao) {
        return banco.fetchOne("select substring(codigo from 3) from referencia_curta where entidade_id = ?", operacao).get(0, String.class);
    }

    private long contar(String consulta, UUID dono) {
        return banco.fetchOne(consulta, dono).get(0, Long.class);
    }
}
