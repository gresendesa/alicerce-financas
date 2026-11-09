package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** As capacidades de escrita pelo protocolo: escopos, formato do erro, {@code snake_case} e esquemas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, KeycloakDeTeste.class})
class CapacidadesMcpTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int porta;

    @Test
    void registrarGastoSemFinWriteResponde403IndicandoOEscopo() throws Exception {
        HttpResponse<String> resposta = chamar(Tokens.doGui("fin:read"), "registrar_gasto", "{}");

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("error=\"insufficient_scope\"", "scope=\"fin:write\"");
    }

    @Test
    void definirContaSemFinStructureResponde403IndicandoOEscopo() throws Exception {
        HttpResponse<String> resposta = chamar(Tokens.doGui("fin:write"), "definir_conta", "{}");

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.headers().firstValue("WWW-Authenticate").orElseThrow())
                .contains("error=\"insufficient_scope\"", "scope=\"fin:structure\"");
    }

    @Test
    void gastoSemMeioDevolveErroTipadoComoResultadoDeTool() throws Exception {
        JsonNode resultado = resultado(chamar(Tokens.doGui("fin:write"), "registrar_gasto", """
                {"chave_idempotencia":"%s","valor":"45.00","descricao":"Almoço","origem":"conversa","categoria":"Mercado"}"""
                .formatted(UUID.randomUUID())));

        assertThat(resultado.get("isError").asBoolean()).isTrue();
        JsonNode erro = texto(resultado).get("erro");
        assertThat(erro.get("codigo").asString()).isEqualTo("campos_faltando");
        assertThat(erro.get("campos_faltando").get(0).asString()).isEqualTo("meio_pagamento");
        assertThat(erro.get("sugestao").asString()).contains("como foi pago");
        assertThat(erro.get("pode_repetir").asBoolean()).isFalse();
    }

    @Test
    void escritaBemSucedidaDevolveOperacaoComNomesEmSnakeCase() throws Exception {
        String token = Tokens.doGui("fin:structure");

        JsonNode saida = texto(resultado(chamar(token, "definir_conta", """
                {"chave_idempotencia":"%s","tipo":"corrente","nome":"Conta %s","saldo_abertura":"10.00"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID()))));

        assertThat(saida.get("repetido").asBoolean()).isFalse();
        assertThat(saida.get("operacao").get("id").asString()).startsWith("o-");
        assertThat(saida.get("operacao").get("url").asString()).contains("/fin/o/");
        assertThat(saida.get("resultado").get("lancamento_abertura").asString()).startsWith("t-");
    }

    @Test
    void itensDeGastoNaoExigemOsCamposOpcionaisNoEsquema() throws Exception {
        HttpResponse<String> resposta = post(Tokens.doGui("fin:read"), """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");

        JsonNode gasto = null;
        for (JsonNode tool : json.readTree(resposta.body()).get("result").get("tools")) {
            if (tool.get("name").asString().equals("registrar_gasto")) {
                gasto = tool;
            }
        }
        assertThat(gasto).isNotNull();
        assertThat(gasto.get("annotations").get("readOnlyHint").asBoolean()).isFalse();
        assertThat(gasto.get("annotations").get("destructiveHint").asBoolean()).isFalse();
        assertThat(gasto.get("annotations").get("idempotentHint").asBoolean()).isTrue();
        assertThat(gasto.get("inputSchema").path("required").isEmpty()).as(gasto.toString()).isTrue();
        assertThat(gasto.get("inputSchema").get("properties").get("itens").get("items").path("required").toString())
                .as(gasto.toString()).doesNotContain("quantidade", "ambito");
    }

    @Test
    void corrigirSemFinWriteResponde403() throws Exception {
        HttpResponse<String> resposta = chamar(Tokens.doGui("fin:read"), "corrigir_lancamento", "{}");

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("scope=\"fin:write\"");
    }

    @Test
    void corrigirListarResolverEDesfazerPeloProtocolo() throws Exception {
        String token = Tokens.doGui("fin:read fin:write fin:structure");
        String sufixo = UUID.randomUUID().toString();
        String conta = "Conta " + sufixo;
        String categoria = "Categoria " + sufixo;
        String meio = "Meio " + sufixo;
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"%s\",\"saldo_abertura\":\"100.00\"}"
                .formatted(UUID.randomUUID(), conta));
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"categoria\",\"nome\":\"%s\"}"
                .formatted(UUID.randomUUID(), categoria));
        escrever(token, "definir_meio_pagamento", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"pix\",\"nome\":\"%s\",\"conta\":\"%s\"}"
                .formatted(UUID.randomUUID(), meio, conta));
        JsonNode gasto = escrever(token, "registrar_gasto", """
                {"chave_idempotencia":"%s","valor":"45.00","descricao":"Almoço","origem":"conversa","meio_pagamento":"%s","categoria":"%s","confirmar_duplicata":true}"""
                .formatted(UUID.randomUUID(), meio, categoria));
        String lancamento = gasto.get("resultado").get("id").asString();

        JsonNode correcao = escrever(token, "corrigir_lancamento", """
                {"chave_idempotencia":"%s","lancamento":"%s","acao":"corrigir","motivo":"valor errado","alteracoes":{"valor":"40.00","meio_pagamento":"%s"}}"""
                .formatted(UUID.randomUUID(), lancamento, meio));
        assertThat(correcao.get("resultado").get("url_novo").asString()).contains("/fin/t/");

        JsonNode novo = texto(resultado(chamar(token, "resolver_referencia",
                "{\"ref\":\"%s\"}".formatted(correcao.get("resultado").get("url_novo").asString()))));
        assertThat(novo.get("valor").asString()).isEqualTo("40.00");
        assertThat(novo.get("meio_pagamento").asString()).isEqualTo(meio);
        assertThat(novo.has("estorna")).as("nulos são omitidos").isFalse();

        JsonNode pagina = texto(resultado(chamar(token, "listar_lancamentos", "{\"meio_pagamento\":\"%s\"}".formatted(meio))));
        assertThat(pagina.get("lancamentos")).hasSize(1);
        JsonNode operacoes = texto(resultado(chamar(token, "listar_operacoes", "{\"capacidade\":\"corrigir_lancamento\"}")));
        String operacao = correcao.get("operacao").get("id").asString();
        assertThat(operacoes.get("operacoes").get(0).get("id").asString()).isEqualTo(operacao);

        JsonNode previa = texto(resultado(chamar(token, "desfazer", "{\"alvo\":\"%s\"}".formatted(operacao))));
        assertThat(previa.get("simulado").asBoolean()).isTrue();
        assertThat(previa.get("operacao").isNull()).isTrue();
        JsonNode aplicado = escrever(token, "desfazer", "{\"alvo\":\"%s\",\"confirmacao\":\"%s\",\"chave_idempotencia\":\"%s\"}"
                .formatted(operacao, previa.get("resultado").get("confirmacao").asString(), UUID.randomUUID()));
        assertThat(aplicado.get("operacao").get("id").asString()).startsWith("o-");
        assertThat(aplicado.get("resultado").get("lancamentos_restabelecidos").get(0).get("id").asString()).isEqualTo(lancamento);
    }

    @Test
    void desfazerSemOEscopoDaOperacaoVoltaComoErroTipado() throws Exception {
        String operacao = escrever(Tokens.doGui("fin:structure"), "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"categoria\",\"nome\":\"Conta %s\"}"
                .formatted(UUID.randomUUID(), UUID.randomUUID())).get("operacao").get("id").asString();

        JsonNode resultado = resultado(chamar(Tokens.doGui("fin:read"), "desfazer", "{\"alvo\":\"%s\"}".formatted(operacao)));

        assertThat(resultado.get("isError").asBoolean()).isTrue();
        assertThat(texto(resultado).get("erro").get("codigo").asString()).isEqualTo("insufficient_scope");
        assertThat(texto(resultado).get("erro").get("mensagem").asString()).contains("fin:structure");
    }

    @Test
    void anotacoesDasNovasCapacidades() throws Exception {
        HttpResponse<String> resposta = post(Tokens.doGui("fin:read"), """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");

        for (JsonNode tool : json.readTree(resposta.body()).get("result").get("tools")) {
            String nome = tool.get("name").asString();
            if (nome.equals("corrigir_lancamento") || nome.equals("desfazer")) {
                assertThat(tool.get("annotations").get("destructiveHint").asBoolean()).as(nome).isTrue();
                assertThat(tool.get("inputSchema").path("required").isEmpty()).as(nome).isTrue();
            }
            if (nome.equals("listar_lancamentos") || nome.equals("listar_operacoes") || nome.equals("resolver_referencia")) {
                assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).as(nome).isTrue();
            }
        }
    }

    @Test
    void compraParceladaPagamentoEFaturaPeloProtocolo() throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String token = Tokens.doGui("fin:read fin:write fin:structure");
        escrever(token, "definir_conta", """
                {"chave_idempotencia":"%s","tipo":"corrente","nome":"Conta %s","data_abertura":"2020-01-01"}""".formatted(UUID.randomUUID(), sufixo));
        escrever(token, "definir_conta", """
                {"chave_idempotencia":"%s","tipo":"categoria","nome":"Compras %s"}""".formatted(UUID.randomUUID(), sufixo));
        JsonNode cartao = escrever(token, "definir_conta", """
                {"chave_idempotencia":"%s","tipo":"cartao_credito","nome":"Cartão %s","data_abertura":"2020-01-01",
                 "cartao":{"dia_fechamento":10,"dia_vencimento":17,"limite":"5000.00"}}""".formatted(UUID.randomUUID(), sufixo));
        assertThat(cartao.get("resultado").get("cartao").get("meio_pagamento").asString()).isEqualTo("Cartão " + sufixo);
        assertThat(cartao.get("resultado").get("marco").asString()).isEqualTo("2020-01-01");

        JsonNode compra = escrever(token, "registrar_gasto", """
                {"chave_idempotencia":"%s","valor":"100.00","descricao":"TV","meio_pagamento":"Cartão %s","origem":"conversa",
                 "categoria":"Compras %s","data":"2026-09-05","parcelas":3,"confirmar_duplicata":true}"""
                .formatted(UUID.randomUUID(), sufixo, sufixo)).get("resultado");
        assertThat(compra.get("valor").asString()).isEqualTo("100.00");
        assertThat(compra.get("credito").get("limite_disponivel").asString()).isEqualTo("4900.00");
        assertThat(compra.get("credito").get("faturas").get(0).get("valor_da_parcela").asString()).isEqualTo("33.34");

        JsonNode fatura = texto(resultado(chamar(token, "obter_fatura", """
                {"cartao":"Cartão %s","periodo":"2026-10"}""".formatted(sufixo))));
        assertThat(fatura.get("total").asString()).isEqualTo("33.33");
        assertThat(fatura.get("itens").get(0).get("parcela").asString()).isEqualTo("2/3");
        assertThat(fatura.get("itens").get(0).get("valor_na_fatura").asString()).isEqualTo("33.33");
        assertThat(fatura.get("itens").get(0).has("valor")).isFalse();

        JsonNode pagamento = escrever(token, "registrar_transferencia", """
                {"chave_idempotencia":"%s","valor":"10.00","de":"Conta %s","ate":"Cartão %s","fatura":"2026-10","data":"2026-10-20"}"""
                .formatted(UUID.randomUUID(), sufixo, sufixo)).get("resultado");
        assertThat(pagamento.get("tipo").asString()).isEqualTo("pagamento_fatura");
        assertThat(pagamento.get("fatura").get("situacao").asString()).isEqualTo("parcialmente_paga");
        assertThat(pagamento.get("fatura").get("restante").asString()).isEqualTo("23.33");

        JsonNode contexto = texto(resultado(chamar(token, "obter_contexto", "{}")));
        JsonNode noContexto = null;
        for (JsonNode c : contexto.get("cartoes")) {
            if (c.get("nome").asString().equals("Cartão " + sufixo)) {
                noContexto = c;
            }
        }
        assertThat(noContexto).isNotNull();
        assertThat(noContexto.get("dia_fechamento").asInt()).isEqualTo(10);
        assertThat(noContexto.get("limite_disponivel").asString()).isEqualTo("4910.00");
    }

    @Test
    void obterFaturaSemFinReadETransferenciaSemFinWriteSaoRecusadas() throws Exception {
        assertThat(chamar(Tokens.doGui("fin:structure"), "obter_fatura", "{}").statusCode()).isEqualTo(403);
        assertThat(chamar(Tokens.doGui("fin:read"), "registrar_transferencia", "{}").statusCode()).isEqualTo(403);
    }

    @Test
    void anexarComprovanteDeclaraOArquivoParaOChatGpt() throws Exception {
        HttpResponse<String> resposta = post(Tokens.doGui("fin:read"), """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");

        JsonNode tool = null;
        for (JsonNode candidata : json.readTree(resposta.body()).get("result").get("tools")) {
            if (candidata.get("name").asString().equals("anexar_comprovante")) {
                tool = candidata;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.get("_meta").get("openai/fileParams").get(0).asString()).isEqualTo("arquivo");
        JsonNode arquivo = tool.get("inputSchema").get("properties").get("arquivo");
        assertThat(arquivo.get("properties").propertyNames()).contains("download_url", "file_id", "mime_type", "file_name");
        assertThat(arquivo.get("required")).extracting(JsonNode::asString).containsExactlyInAnyOrder("download_url", "file_id");
    }

    @Test
    void anexarSemFinWriteResponde403() throws Exception {
        assertThat(chamar(Tokens.doGui("fin:read"), "anexar_comprovante", "{}").statusCode()).isEqualTo(403);
    }

    @Test
    void moedaOriginalEResumoPeloProtocolo() throws Exception {
        String token = Tokens.doGui("fin:read fin:write fin:structure");
        String sufixo = UUID.randomUUID().toString();
        String conta = "Conta " + sufixo;
        String categoria = "Categoria " + sufixo;
        String meio = "Meio " + sufixo;
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"%s\",\"saldo_abertura\":\"1000.00\"}"
                .formatted(UUID.randomUUID(), conta));
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"categoria\",\"nome\":\"%s\"}"
                .formatted(UUID.randomUUID(), categoria));
        escrever(token, "definir_meio_pagamento", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"pix\",\"nome\":\"%s\",\"conta\":\"%s\"}"
                .formatted(UUID.randomUUID(), meio, conta));

        JsonNode gasto = escrever(token, "registrar_gasto", """
                {"chave_idempotencia":"%s","valor_original":"20.00","moeda_original":"USD","cotacao":"5.52","descricao":"Assinatura","data":"2031-03-10","origem":"conversa","meio_pagamento":"%s","categoria":"%s"}"""
                .formatted(UUID.randomUUID(), meio, categoria)).get("resultado");
        assertThat(gasto.get("valor").asString()).isEqualTo("110.40");
        assertThat(gasto.get("original").get("moeda_original").asString()).isEqualTo("USD");

        JsonNode lido = texto(resultado(chamar(token, "resolver_referencia", "{\"ref\":\"%s\"}".formatted(gasto.get("id").asString()))));
        assertThat(lido.get("original").get("valor_original").asString()).isEqualTo("20.00");
        assertThat(lido.get("original").get("cotacao").asString()).isEqualTo("5.52");
        assertThat(lido.get("original").get("data_cotacao").asString()).isEqualTo("2031-03-10");

        // Só com fin:read: um relatório é leitura, ainda que guarde o retrato.
        String leitura = Tokens.doGui("fin:read");
        JsonNode resumo = texto(resultado(chamar(leitura, "resumo_periodo", """
                {"periodo":"2031-03","agrupar_por":["categoria","meio_pagamento"]}""")));
        assertThat(resumo.get("resumo").get("saidas").asString()).isEqualTo("110.40");
        assertThat(resumo.get("retrato").asString()).startsWith("s-");
        assertThat(resumo.get("url_retrato").asString()).contains("/fin/s/");
        assertThat(resumo.get("url_vista_viva").asString()).contains("/fin/relatorios?de=2031-03-01&ate=2031-03-31");

        JsonNode retrato = texto(resultado(chamar(leitura, "resolver_referencia",
                "{\"ref\":\"%s\",\"comparar_agora\":true}".formatted(resumo.get("url_retrato").asString()))));
        assertThat(retrato.get("resultado")).isEqualTo(resumo.get("resumo"));
        assertThat(retrato.get("parametros").get("de").asString()).isEqualTo("2031-03-01");
        assertThat(retrato.get("comparacao").get("mudou").asBoolean()).isFalse();
    }

    @Test
    void contaEmUsdECambioPeloMcpComSaldosConvertidos() throws Exception {
        String token = Tokens.doGui("fin:read fin:write fin:structure");
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String real = "Real " + sufixo;
        String dolar = "Dolar " + sufixo;
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"%s\",\"saldo_abertura\":\"10000.00\",\"data_abertura\":\"2020-01-01\"}"
                .formatted(UUID.randomUUID(), real));
        JsonNode conta = escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"%s\",\"moeda\":\"USD\",\"data_abertura\":\"2020-01-01\"}"
                .formatted(UUID.randomUUID(), dolar)).get("resultado");
        assertThat(conta.get("moeda").asString()).isEqualTo("USD");

        JsonNode recusa = resultado(chamar(token, "registrar_transferencia", """
                {"chave_idempotencia":"%s","valor":"5500.00","de":"%s","ate":"%s"}""".formatted(UUID.randomUUID(), real, dolar)));
        assertThat(recusa.get("isError").asBoolean()).isTrue();
        assertThat(texto(recusa).get("erro").get("campos_faltando").toString()).contains("cotacao");

        JsonNode cambio = escrever(token, "registrar_transferencia", """
                {"chave_idempotencia":"%s","valor":"5500.00","cotacao":"5.50","de":"%s","ate":"%s","data":"2031-05-10"}""".formatted(UUID.randomUUID(), real, dolar)).get("resultado");
        assertThat(cambio.get("original").get("valor_original").asString()).isEqualTo("1000.00");
        assertThat(cambio.get("saldos").get(0).get("conta").asString()).isEqualTo(dolar);
        assertThat(cambio.get("saldos").get(0).get("moeda").asString()).isEqualTo("USD");

        JsonNode contexto = texto(resultado(chamar(token, "obter_contexto", "{}")));
        boolean achou = false;
        for (JsonNode c : contexto.get("contas")) {
            achou |= c.get("nome").asString().equals(dolar) && c.get("moeda").asString().equals("USD") && c.get("saldo").asString().equals("1000.00");
        }
        assertThat(achou).isTrue();

        JsonNode saldos = texto(resultado(chamar(token, "resumo_periodo", "{\"periodo\":\"2031-05\"}"))).get("resumo").get("saldos");
        JsonNode linha = null;
        for (JsonNode c : saldos.get("contas")) {
            linha = c.get("nome").asString().equals(dolar) ? c : linha;
        }
        assertThat(linha.get("saldo").asString()).isEqualTo("1000.00");
        assertThat(linha.get("cotacao").asString()).isEqualTo("5.5");
        assertThat(linha.get("data_cotacao").asString()).isEqualTo("2031-05-10");
        assertThat(linha.get("saldo_convertido").asString()).isEqualTo("5500.00");
        assertThat(linha.get("moeda_saldo_convertido").asString()).isEqualTo("BRL");
    }

    @Test
    void resumoPeriodoEhLeituraNoCatalogo() throws Exception {
        HttpResponse<String> resposta = post(Tokens.doGui("fin:read"), """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");

        JsonNode tool = null;
        for (JsonNode candidata : json.readTree(resposta.body()).get("result").get("tools")) {
            if (candidata.get("name").asString().equals("resumo_periodo")) {
                tool = candidata;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(tool.get("inputSchema").path("required").isEmpty()).as(tool.toString()).isTrue();
        assertThat(tool.get("inputSchema").get("properties").get("agrupar_por").get("type").asString()).isEqualTo("array");
    }

    // conferência, compromissos, agenda e conta conectada

    @Test
    void definirCompromissoExigeFinProfile() throws Exception {
        HttpResponse<String> resposta = chamar(Tokens.doGui("fin:read fin:write fin:structure"), "definir_compromisso", "{}");

        assertThat(resposta.statusCode()).isEqualTo(403);
        assertThat(resposta.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("scope=\"fin:profile\"");
    }

    @Test
    void compromissoAgendaEConferenciaPeloProtocolo() throws Exception {
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure fin:profile");
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"Corrente %s\",\"saldo_abertura\":\"500.00\"}"
                .formatted(UUID.randomUUID(), sufixo));
        JsonNode criado = escrever(token, "definir_compromisso", """
                {"chave_idempotencia":"%s","direcao":"saida","tipo":"parcelado","nome":"Parcelamento %s","valor_total":"1000.00","parcelas":4,
                 "primeiro_vencimento":"2030-01-15","referencia":"PARC-%s","contraparte":"Receita"}""".formatted(UUID.randomUUID(), sufixo, sufixo));
        JsonNode compromisso = criado.get("resultado");
        assertThat(compromisso.get("id").asString()).startsWith("k-");
        assertThat(compromisso.get("saldo").get("valor_restante").asString()).isEqualTo("1000.00");
        assertThat(compromisso.get("proximas").get(0).get("valor_previsto").asString()).isEqualTo("250.00");
        assertThat(compromisso.get("proximas").get(0).get("parcela").asString()).isEqualTo("1/4");

        JsonNode lista = texto(resultado(chamar(token, "listar_compromissos", "{\"texto\":\"PARC-%s\"}".formatted(sufixo))));
        assertThat(lista.get("compromissos").get(0).get("referencia").asString()).isEqualTo("PARC-" + sufixo);
        assertThat(lista.get("compromissos").get(0).get("proxima").get("vencimento").asString()).isEqualTo("2030-01-15");

        JsonNode agenda = texto(resultado(chamar(token, "obter_agenda", "{\"de\":\"2030-01-01\",\"ate\":\"2030-01-31\"}")));
        assertThat(agenda.get("vencimentos").get(0).get("id").asString()).isEqualTo(compromisso.get("id").asString());
        assertThat(agenda.get("totais").get("a_pagar_no_periodo").asString()).isEqualTo("250.00");

        JsonNode conferencia = escrever(token, "conferir_saldo", "{\"chave_idempotencia\":\"%s\",\"conta\":\"Corrente %s\",\"saldo\":\"480.00\"}"
                .formatted(UUID.randomUUID(), sufixo)).get("resultado");
        assertThat(conferencia.get("situacao").asString()).isEqualTo("pendente");
        assertThat(conferencia.get("diferenca").asString()).isEqualTo("-20.00");
        assertThat(conferencia.get("saldo_calculado").asString()).isEqualTo("500.00");
    }

    @Test
    void situacaoPeloProtocoloTrazOAtrasoComOsMesmosNumerosDaAgenda() throws Exception {
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure fin:profile");
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"Corrente %s\",\"saldo_abertura\":\"500.00\"}"
                .formatted(UUID.randomUUID(), sufixo));
        String id = escrever(token, "definir_compromisso", """
                {"chave_idempotencia":"%s","direcao":"saida","tipo":"unico","nome":"Taxa %s","valor":"80.00",
                 "primeiro_vencimento":"2020-01-15","referencia":"TAXA-%s"}""".formatted(UUID.randomUUID(), sufixo, sufixo))
                .get("resultado").get("id").asString();

        JsonNode situacao = texto(resultado(chamar(Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read"), "situacao", "{}")));
        JsonNode agenda = texto(resultado(chamar(token, "obter_agenda", "{}")));

        JsonNode sinal = null;
        for (JsonNode s : situacao.get("sinais")) {
            if (id.equals(s.path("id").asString())) {
                sinal = s;
            }
        }
        assertThat(sinal).as(situacao.toString()).isNotNull();
        assertThat(sinal.get("codigo").asString()).isEqualTo("compromisso_atrasado");
        assertThat(sinal.get("severidade").asString()).isEqualTo("critico");
        assertThat(sinal.get("alvo").asString()).isEqualTo("compromisso");
        assertThat(sinal.get("valor").asString()).isEqualTo("80.00");
        assertThat(sinal.get("dias_atraso").asLong()).isGreaterThan(30);
        assertThat(sinal.get("url").asString()).contains("/fin/");
        JsonNode naAgenda = null;
        for (JsonNode i : agenda.get("atrasados")) {
            if (id.equals(i.path("id").asString())) {
                naAgenda = i;
            }
        }
        assertThat(naAgenda).isNotNull();
        assertThat(sinal.get("valor").asString()).isEqualTo(naAgenda.get("valor").asString());
        assertThat(sinal.get("vencimento").asString()).isEqualTo(naAgenda.get("vencimento").asString());
        assertThat(situacao.get("data_referencia").asString()).isEqualTo(agenda.get("data_referencia").asString());
        assertThat(situacao.get("contagem").get("critico").asInt()).isPositive();
    }

    @Test
    void parcelaPagaEmDuasVezesPeloProtocolo() throws Exception {
        // definir_compromisso exige fin:profile, como no teste de compromisso pelo protocolo.
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure fin:profile");
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String conta = "Conta " + sufixo;
        String meio = "Meio " + sufixo;
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"corrente\",\"nome\":\"%s\",\"saldo_abertura\":\"1000.00\"}"
                .formatted(UUID.randomUUID(), conta));
        escrever(token, "definir_conta", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"categoria\",\"nome\":\"Dívidas %s\"}"
                .formatted(UUID.randomUUID(), sufixo));
        escrever(token, "definir_meio_pagamento", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"pix\",\"nome\":\"%s\",\"conta\":\"%s\"}"
                .formatted(UUID.randomUUID(), meio, conta));
        String id = escrever(token, "definir_compromisso", """
                {"chave_idempotencia":"%s","direcao":"saida","tipo":"parcelado","nome":"Acordo %s","valor":"150.00","parcelas":2,
                 "primeiro_vencimento":"2031-05-10","referencia":"ACORDO-%s","conta":"%s"}""".formatted(UUID.randomUUID(), sufixo, sufixo, conta))
                .get("resultado").get("id").asString();
        String pagar = """
                {"chave_idempotencia":"%s","valor":"%s","descricao":"Acordo","data":"%s","origem":"conversa","meio_pagamento":"%s",
                 "categoria":"Dívidas %s","compromisso":"%s","ocorrencia":"1"}""";

        JsonNode parte = escrever(token, "registrar_gasto", pagar.formatted(UUID.randomUUID(), "100.00", "2031-05-10", meio, sufixo, id))
                .get("resultado").get("ocorrencia");
        assertThat(parte.get("situacao").asString()).isEqualTo("prevista");
        assertThat(parte.get("valor_pago").asString()).isEqualTo("100.00");
        assertThat(parte.get("falta_na_ocorrencia").asString()).isEqualTo("50.00");
        assertThat(parte.get("valor_restante").asString()).isEqualTo("200.00");

        JsonNode lista = texto(resultado(chamar(token, "listar_compromissos", "{\"texto\":\"ACORDO-%s\"}".formatted(sufixo))));
        assertThat(lista.get("compromissos").get(0).get("saldo").get("valor_restante").asString()).isEqualTo("200.00");
        JsonNode agenda = texto(resultado(chamar(token, "obter_agenda", "{\"de\":\"2031-05-01\",\"ate\":\"2031-05-31\"}")));
        assertThat(agenda.get("totais").get("a_pagar_no_periodo").asString()).isEqualTo("50.00");

        JsonNode quitada = escrever(token, "registrar_gasto", pagar.formatted(UUID.randomUUID(), "50.00", "2031-05-20", meio, sufixo, id))
                .get("resultado").get("ocorrencia");
        assertThat(quitada.get("situacao").asString()).isEqualTo("paga");
        assertThat(quitada.get("valor_pago").asString()).isEqualTo("150.00");
        assertThat(quitada.get("falta_na_ocorrencia").isNull()).as("escrita manda o campo nulo").isTrue();
    }

    @Test
    void contextoDizQualContaEstaConectadaPeloLoginDoToken() throws Exception {
        JsonNode real = texto(resultado(chamar(Tokens.doGui("fin:read"), "obter_contexto", "{}"))).get("conta_conectada");
        JsonNode teste = texto(resultado(chamar(Tokens.daAna("fin:read"), "obter_contexto", "{}"))).get("conta_conectada");

        assertThat(real.get("usuario").asString()).isEqualTo("gui");
        assertThat(real.get("cliente").asString()).isEqualTo("claude-ai");
        assertThat(real.get("conta_de_teste").asBoolean()).isFalse();
        assertThat(teste.get("usuario").asString()).isEqualTo("ana");
        assertThat(teste.get("conta_de_teste").asBoolean()).isTrue();
    }

    @Test
    void toolsNovasDeclaramAnotacoesEOAvisoSobreSenha() throws Exception {
        HttpResponse<String> resposta = post(Tokens.doGui("fin:read"), """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""");
        java.util.Map<String, JsonNode> tools = new java.util.HashMap<>();
        for (JsonNode tool : json.readTree(resposta.body()).get("result").get("tools")) {
            tools.put(tool.get("name").asString(), tool);
        }

        for (String leitura : List.of("listar_compromissos", "obter_agenda", "situacao")) {
            assertThat(tools.get(leitura).get("annotations").get("readOnlyHint").asBoolean()).as(leitura).isTrue();
            assertThat(tools.get(leitura).get("annotations").get("destructiveHint").asBoolean()).as(leitura).isFalse();
            assertThat(tools.get(leitura).get("annotations").get("idempotentHint").asBoolean()).as(leitura).isTrue();
        }
        // Só o horizonte opcional dos sinais do perfil.
        assertThat(tools.get("situacao").get("inputSchema").path("properties").propertyNames()).as("situacao só tem horizonte_meses")
                .containsExactly("horizonte_meses");
        assertThat(tools.get("situacao").get("inputSchema").path("required").isEmpty()).isTrue();
        assertThat(tools.get("situacao").get("description").asString()).contains("tarefas agendadas", "nada é gravado", "links");
        for (String escrita : List.of("definir_compromisso", "conferir_saldo", "resolver_ocorrencia")) {
            JsonNode tool = tools.get(escrita);
            assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).as(escrita).isFalse();
            assertThat(tool.get("annotations").get("destructiveHint").asBoolean()).as(escrita).isFalse();
            assertThat(tool.get("inputSchema").path("required").isEmpty()).as(escrita).isTrue();
        }
        JsonNode definir = tools.get("definir_compromisso");
        assertThat(definir.get("description").asString()).contains("NUNCA guarde ali", "senha");
        assertThat(definir.get("inputSchema").get("properties").get("referencia").get("description").asString()).contains("Nunca senha");
        assertThat(tools.get("registrar_gasto").get("inputSchema").get("properties").has("compromisso")).isTrue();
        assertThat(tools.get("registrar_ganho").get("inputSchema").get("properties").has("ocorrencia")).isTrue();
    }

    @Test
    void ritmoGastosSoComFinReadGuardaRetratoEDevolveSnakeCase() throws Exception {
        String leitura = Tokens.doGui("fin:read");

        JsonNode saida = texto(resultado(chamar(leitura, "ritmo_gastos", "{\"meses_referencia\":3,\"visao\":\"caixa\"}")));

        assertThat(saida.get("retrato").asString()).startsWith("s-");
        assertThat(saida.get("url_retrato").asString()).contains("/fin/s/");
        JsonNode ritmo = saida.get("ritmo");
        assertThat(ritmo.get("visao").asString()).isEqualTo("caixa");
        assertThat(ritmo.get("em_curso").asBoolean()).isTrue();
        assertThat(ritmo.has("acumulado")).isTrue();
        assertThat(ritmo.get("projecoes").has("mantido_ritmo_atual")).isTrue();
        assertThat(ritmo.get("nota_fixos").asString()).contains("incluir_fixos");

        JsonNode retrato = texto(resultado(chamar(leitura, "resolver_referencia", "{\"ref\":\"%s\"}".formatted(saida.get("retrato").asString()))));
        assertThat(retrato.get("capacidade").asString()).isEqualTo("ritmo_gastos");
        assertThat(retrato.get("resultado").get("acumulado")).isEqualTo(ritmo.get("acumulado"));

        JsonNode recusa = resultado(chamar(leitura, "ritmo_gastos", "{\"meses_referencia\":1}"));
        assertThat(recusa.get("isError").asBoolean()).isTrue();
        assertThat(texto(recusa).get("erro").get("campo").asString()).isEqualTo("meses_referencia");

        JsonNode tool = null;
        for (JsonNode candidata : json.readTree(post(leitura, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            if (candidata.get("name").asString().equals("ritmo_gastos")) {
                tool = candidata;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(tool.get("annotations").get("idempotentHint").asBoolean()).isTrue();
        assertThat(tool.get("inputSchema").path("required").isEmpty()).isTrue();
    }

    @Test
    void declararExigeFinProfileECortesNecessariosLeComFinRead() throws Exception {
        assertThat(chamar(Tokens.doGui("fin:read fin:write fin:structure"), "declarar", "{}").statusCode()).isEqualTo(403);
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure fin:profile");

        JsonNode faltando = texto(resultado(chamar(token, "declarar", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"piso_periodo\"}"
                .formatted(UUID.randomUUID()))));
        assertThat(faltando.get("erro").get("campos_faltando").get(0).asString()).isEqualTo("saldo_minimo");
        JsonNode declarada = escrever(token, "declarar", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"piso_periodo\",\"saldo_minimo\":\"0.01\"}"
                .formatted(UUID.randomUUID()));
        assertThat(declarada.get("resultado").get("declaracao").get("saldo_minimo").asString()).isEqualTo("0.01");
        assertThat(declarada.get("resultado").get("declaracao").get("contas_pagas").asBoolean()).isTrue();
        String operacao = declarada.get("operacao").get("id").asString();

        JsonNode contexto = texto(resultado(chamar(token, "obter_contexto", "{}")));
        assertThat(contexto.get("declaracoes").get(0).get("tipo").asString()).isEqualTo("piso_periodo");
        assertThat(contexto.get("declaracoes").get(0).get("saldo_minimo").asString()).isEqualTo("0.01");

        JsonNode cortes = texto(resultado(chamar(Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read"),
                "cortes_necessarios", "{}")));
        assertThat(cortes.get("retrato").asString()).startsWith("s-");
        assertThat(cortes.get("cortes").get("alvo").get("tipo").asString()).isEqualTo("piso_periodo");
        assertThat(cortes.get("cortes").has("saldo_projetado")).isTrue();
        assertThat(cortes.get("cortes").has("nao_cortaveis")).isTrue();

        // Desfazer pelo assistente tira a declaração do contexto.
        String confirmacao = texto(resultado(chamar(token, "desfazer", "{\"alvo\":\"%s\"}".formatted(operacao)))).get("resultado")
                .get("confirmacao").asString();
        escrever(token, "desfazer", "{\"chave_idempotencia\":\"%s\",\"alvo\":\"%s\",\"confirmacao\":\"%s\"}"
                .formatted(UUID.randomUUID(), operacao, confirmacao));
        assertThat(texto(resultado(chamar(token, "obter_contexto", "{}"))).get("declaracoes").isEmpty()).isTrue();

        java.util.Map<String, JsonNode> tools = new java.util.HashMap<>();
        for (JsonNode tool : json.readTree(post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            tools.put(tool.get("name").asString(), tool);
        }
        JsonNode declarar = tools.get("declarar");
        assertThat(declarar.get("annotations").get("readOnlyHint").asBoolean()).isFalse();
        assertThat(declarar.get("annotations").get("destructiveHint").asBoolean()).isFalse();
        assertThat(declarar.get("inputSchema").path("required").isEmpty()).isTrue();
        JsonNode corte = tools.get("cortes_necessarios");
        assertThat(corte.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(corte.get("annotations").get("idempotentHint").asBoolean()).isTrue();
        assertThat(corte.get("description").asString()).contains("nunca some nem recalcule", "ressalvas");
    }

    @Test
    void declararOsTiposDoPerfilESituacaoDizOQueNaoFoiAvaliado() throws Exception {
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "cia", "fin:read fin:write fin:structure fin:profile");
        JsonNode reserva = escrever(token, "declarar", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"reserva_minima\",\"meses_de_gasto\":3}"
                .formatted(UUID.randomUUID()));
        assertThat(reserva.get("resultado").get("declaracao").get("meses_de_gasto").asInt()).isEqualTo(3);
        assertThat(reserva.get("resultado").path("anterior").isNull()).isTrue();
        escrever(token, "declarar", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"nota\",\"texto\":\"Não gosto de parcelar.\"}"
                .formatted(UUID.randomUUID()));
        JsonNode errado = texto(resultado(chamar(token, "declarar", "{\"chave_idempotencia\":\"%s\",\"tipo\":\"teto_parcelas\"}"
                .formatted(UUID.randomUUID()))));
        assertThat(errado.get("erro").get("campos_faltando").get(0).asString()).isEqualTo("percentual");

        JsonNode contexto = texto(resultado(chamar(token, "obter_contexto", "{}")));
        java.util.Map<String, JsonNode> declaracoes = new java.util.HashMap<>();
        contexto.get("declaracoes").forEach(d -> declaracoes.put(d.get("tipo").asString(), d));
        assertThat(declaracoes.get("nota").get("texto").asString()).isEqualTo("Não gosto de parcelar.");
        assertThat(declaracoes.get("reserva_minima").get("meses_de_gasto").asInt()).isEqualTo(3);

        JsonNode situacao = texto(resultado(chamar(token, "situacao", "{\"horizonte_meses\":2}")));
        assertThat(situacao.get("horizonte_meses").asInt()).isEqualTo(2);
        java.util.List<String> naoAvaliados = new java.util.ArrayList<>();
        situacao.get("nao_avaliados").forEach(n -> naoAvaliados.add(n.get("declaracao_que_falta").asString()));
        assertThat(naoAvaliados).containsExactly("teto_parcelas", "intencao_poupanca");

        JsonNode tool = null;
        for (JsonNode candidata : json.readTree(post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            if (candidata.get("name").asString().equals("declarar")) {
                tool = candidata;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.get("inputSchema").toString()).contains("meses_de_gasto", "percentual", "metas", "texto");
        assertThat(tool.get("description").asString()).contains("reserva_minima", "intencao_poupanca", "teto_parcelas", "meta_protegida", "nota");
    }

    @Test
    void projetarLeComFinReadCenariosAninhadosEGuardaRetrato() throws Exception {
        String token = Tokens.doGui("fin:read");
        JsonNode projecao = texto(resultado(chamar(token, "projetar", """
                {"horizonte_meses":2,"cenarios":[{"rotulo":"aumento","hipoteses":[{"tipo":"mudanca_renda","valor":"100.00"}]}]}""")));
        assertThat(projecao.get("retrato").asString()).startsWith("s-");
        assertThat(projecao.get("projecao").get("linha_base").get("meses")).hasSize(2);
        JsonNode cenario = projecao.get("projecao").get("cenarios").get(0);
        assertThat(cenario.get("rotulo").asString()).isEqualTo("aumento");
        assertThat(cenario.get("diferenca_linha_base").get("meses").get(0).get("saldo_final_p50").asString()).isEqualTo("0.00");
        assertThat(cenario.get("diferenca_linha_base").get("meses").get(1).get("saldo_final_p80").asString()).isEqualTo("100.00");

        JsonNode erro = texto(resultado(chamar(token, "projetar", """
                {"cenarios":[{"hipoteses":[{"tipo":"compra","valor":"10.00"}]}]}""")));
        assertThat(erro.get("erro").get("campos_faltando").get(0).asString()).isEqualTo("cenarios[0].hipoteses[0].meio_pagamento");

        JsonNode tool = null;
        for (JsonNode candidata : json.readTree(post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            if (candidata.get("name").asString().equals("projetar")) {
                tool = candidata;
            }
        }
        assertThat(tool).isNotNull();
        assertThat(tool.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(tool.get("annotations").get("openWorldHint").asBoolean()).isFalse();
        assertThat(tool.get("inputSchema").path("required").isEmpty()).isTrue();
        assertThat(tool.get("inputSchema").toString()).contains("meio_pagamento", "tipo_compromisso", "primeiro_vencimento", "rotulo");
        assertThat(tool.get("description").asString()).contains("posso comprar isso?", "nunca some nem recalcule", "ressalvas", "p80");
    }

    @Test
    void oGuiaCitaSoToolsQueExistemTodasElasETodosOsPrefixos() throws Exception {
        String token = Tokens.doGui("fin:read");
        java.util.Set<String> existentes = new java.util.TreeSet<>();
        for (JsonNode tool : json.readTree(post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            existentes.add(tool.get("name").asString());
        }
        JsonNode resultado = resultado(chamar(token, "guia", "{}"));
        assertThat(resultado.path("isError").asBoolean(false)).isFalse();
        String guia = resultado.get("content").get(0).get("text").asString();

        java.util.Set<String> citadas = new java.util.TreeSet<>();
        java.util.regex.Matcher crase = java.util.regex.Pattern.compile("`([^`]+)`").matcher(guia);
        while (crase.find()) {
            citadas.add(crase.group(1));
        }
        // Entre crases só vão tools: cada uma existe, e nenhuma tool do servidor fica fora do guia.
        assertThat(existentes).containsAll(citadas);
        assertThat(citadas).containsExactlyElementsOf(existentes);
        for (tools.alicerce.financeiro.dominio.TipoReferencia tipo : tools.alicerce.financeiro.dominio.TipoReferencia.values()) {
            assertThat(guia).as(tipo.name()).contains("- " + tipo.prefixo() + "-: ");
        }
        assertThat(guia).contains("nunca pela memória da conversa", "não invente", "campos_faltando", "conta_de_teste", "sem login");
    }

    @Test
    void oInitializeMandaInstrucoesCurtasQueApontamParaOGuia() throws Exception {
        JsonNode iniciado = json.readTree(post(Tokens.doGui("fin:read"), """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},                "clientInfo":{"name":"t","version":"1"}}}""").body()).get("result");
        String instrucoes = iniciado.get("instructions").asString();
        assertThat(instrucoes).contains("guia", "obter_contexto", "resolver_referencia", "nunca pela memória da conversa");
        assertThat(instrucoes.length()).isLessThan(600);
    }

    @Test
    void prestarContasExigeFinSharePeloProtocoloEDevolveALinkSoNaResposta() throws Exception {
        HttpResponse<String> semEscopo = chamar(Tokens.doGui("fin:read fin:write fin:structure fin:profile"), "prestar_contas", "{}");
        assertThat(semEscopo.statusCode()).isEqualTo(403);
        assertThat(semEscopo.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("error=\"insufficient_scope\"", "scope=\"fin:share\"");

        String token = Tokens.doGui("fin:read fin:structure fin:share");
        String ambito = "Prestação " + UUID.randomUUID().toString().substring(0, 8);
        escrever(token, "definir_ambito", "{\"chave_idempotencia\":\"%s\",\"nome\":\"%s\"}".formatted(UUID.randomUUID(), ambito));
        JsonNode faltando = texto(resultado(chamar(token, "prestar_contas", "{\"periodo\":\"2026-09\"}")));
        assertThat(faltando.get("erro").get("campos_faltando").get(0).asString()).isEqualTo("ambito");

        JsonNode criada = escrever(token, "prestar_contas", "{\"ambito\":\"%s\",\"periodo\":\"2026-09\"}".formatted(ambito));
        String url = criada.get("resultado").get("url_publica").asString();
        assertThat(url).contains("/fin/p/");
        assertThat(criada.get("resultado").get("o_que_mostra").asString()).contains("sem login");
        assertThat(criada.get("operacao").get("id").asString()).startsWith("o-");
        HttpResponse<String> pagina = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + URI.create(url).getRawPath()))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pagina.statusCode()).isEqualTo(200);

        JsonNode revogada = escrever(token, "prestar_contas", "{\"revogar\":\"%s\"}".formatted(url));
        assertThat(revogada.get("resultado").get("revogado").asBoolean()).isTrue();
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + URI.create(url).getRawPath())).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);

        java.util.Map<String, JsonNode> tools = new java.util.HashMap<>();
        for (JsonNode tool : json.readTree(post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            tools.put(tool.get("name").asString(), tool);
        }
        JsonNode prestar = tools.get("prestar_contas");
        assertThat(prestar.get("annotations").get("readOnlyHint").asBoolean()).isFalse();
        assertThat(prestar.get("annotations").get("destructiveHint").asBoolean()).isFalse();
        // Com openWorldHint true, o ChatGPT bloqueou a chamada no teste real.
        assertThat(prestar.get("annotations").get("openWorldHint").asBoolean()).isFalse();
        assertThat(prestar.get("inputSchema").path("required").isEmpty()).isTrue();
        assertThat(prestar.get("description").asString()).contains("SEM LOGIN", "Nunca mostra saldos", "fin:share", "reconectar");
        JsonNode guia = tools.get("guia");
        assertThat(guia.get("annotations").get("readOnlyHint").asBoolean()).isTrue();
        assertThat(guia.get("description").asString()).contains("marco", "antes de responder de cabeça");
        assertThat(tools.get("resolver_referencia").get("description").asString()).contains("a-MY9RNB", "nunca pela memória");
        assertThat(tools.get("obter_contexto").get("description").asString()).contains("guia");
    }

    @Test
    void definirMetaExigeFinProfileEAMetaApareceNoContextoNaReferenciaENaProjecao() throws Exception {
        assertThat(chamar(Tokens.doGui("fin:read fin:write fin:structure"), "definir_meta", "{}").statusCode()).isEqualTo(403);
        String token = Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read fin:write fin:structure fin:profile");
        String nome = "Meta " + UUID.randomUUID().toString().substring(0, 8);
        String alvo = java.time.YearMonth.now(java.time.ZoneId.of("America/Sao_Paulo")).plusMonths(9).atEndOfMonth().toString();

        JsonNode faltando = texto(resultado(chamar(token, "definir_meta", "{\"chave_idempotencia\":\"%s\",\"nome\":\"%s\"}"
                .formatted(UUID.randomUUID(), nome))));
        assertThat(faltando.get("erro").get("campos_faltando").get(0).asString()).isEqualTo("valor_alvo");
        JsonNode criada = escrever(token, "definir_meta", "{\"chave_idempotencia\":\"%s\",\"nome\":\"%s\",\"valor_alvo\":\"1000.00\",\"data_alvo\":\"%s\"}"
                .formatted(UUID.randomUUID(), nome, alvo)).get("resultado");
        String id = criada.get("id").asString();
        assertThat(id).startsWith("m-");
        assertThat(criada.get("progresso").asString()).isEqualTo("0.00");
        assertThat(criada.get("falta").asString()).isEqualTo("1000.00");
        assertThat(criada.get("aporte_mensal_necessario").asString()).isEqualTo("100.00");

        JsonNode contexto = texto(resultado(chamar(token, "obter_contexto", "{}")));
        JsonNode daMeta = null;
        for (JsonNode m : contexto.get("metas")) {
            if (m.get("id").asString().equals(id)) {
                daMeta = m;
            }
        }
        assertThat(daMeta).isNotNull();
        assertThat(daMeta.get("valor_alvo").asString()).isEqualTo("1000.00");
        assertThat(daMeta.get("aporte_mensal_necessario").asString()).isEqualTo("100.00");
        JsonNode resolvida = texto(resultado(chamar(Tokens.obter("claude-ai", KeycloakDeTeste.SEGREDO_CLAUDE, "bia", "fin:read"),
                "resolver_referencia", "{\"ref\":\"%s\"}".formatted(id))));
        assertThat(resolvida.get("nome").asString()).isEqualTo(nome);

        JsonNode projecao = texto(resultado(chamar(token, "projetar", """
                {"horizonte_meses":12,"cenarios":[{"rotulo":"guardar","hipoteses":[{"tipo":"aporte_meta","meta":"%s","valor":"250.00"}]}]}"""
                .formatted(id))));
        JsonNode cenario = projecao.get("projecao").get("cenarios").get(0);
        assertThat(cenario.get("meses").get(0).get("aportes").asString()).isEqualTo("250.00");
        assertThat(cenario.get("meses").get(4).get("aportes").asString()).isEqualTo("0.00");
        JsonNode projetada = null;
        for (JsonNode m : cenario.get("metas")) {
            if (m.get("id").asString().equals(id)) {
                projetada = m;
            }
        }
        assertThat(projetada.get("mes_projetado").asString()).isEqualTo(java.time.YearMonth.now(java.time.ZoneId.of("America/Sao_Paulo")).plusMonths(3).toString());
        assertThat(projetada.get("no_prazo").asBoolean()).isTrue();

        java.util.Map<String, JsonNode> tools = new java.util.HashMap<>();
        for (JsonNode tool : json.readTree(post(token, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""").body()).get("result").get("tools")) {
            tools.put(tool.get("name").asString(), tool);
        }
        JsonNode definir = tools.get("definir_meta");
        assertThat(definir.get("annotations").get("readOnlyHint").asBoolean()).isFalse();
        assertThat(definir.get("annotations").get("destructiveHint").asBoolean()).isFalse();
        assertThat(definir.get("annotations").get("openWorldHint").asBoolean()).isFalse();
        assertThat(definir.get("inputSchema").path("required").isEmpty()).isTrue();
        assertThat(definir.get("description").asString()).contains("aporte_mensal_necessario", "nunca recalcule", "registrar_transferencia");
        assertThat(tools.get("registrar_transferencia").get("inputSchema").toString()).contains("\"meta\"", "\"resgate\"");
        assertThat(tools.get("cortes_necessarios").get("inputSchema").toString()).contains("\"meta\"");
        assertThat(tools.get("projetar").get("inputSchema").toString()).contains("aporte_meta", "\"ate\"");
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
        return post(token, """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
                .formatted(tool, argumentos));
    }

    private HttpResponse<String> post(String token, String corpo) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + porta + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-11-25")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
