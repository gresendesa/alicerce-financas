package tools.alicerce.financeiro.adaptador.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.System.Logger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.OperacaoRegistrada;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traduz o resultado de uma escrita para o protocolo: nomes em {@code snake_case}, e toda falha no
 * mesmo formato, como resultado de tool com {@code isError}.
 */
final class Respostas {

    private static final Logger LOG = System.getLogger(Respostas.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    /** Leituras: campos nulos são omitidos, para as listas ficarem compactas. */
    private static final JsonMapper LEITURA = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private Respostas() {}

    static CallToolResult leitura(Supplier<?> leitura) {
        try {
            return CallToolResult.builder().addTextContent(LEITURA.writeValueAsString(leitura.get())).build();
        } catch (ErroTipado erro) {
            return falha(erro);
        } catch (RuntimeException erro) {
            LOG.log(Logger.Level.ERROR, "Falha inesperada numa capacidade", erro);
            return falha(ErroTipado.de(CodigoErro.ERRO_INTERNO, "Erro interno. Tente de novo em instantes."));
        }
    }

    /** Uma vista: o MCP App lê {@code structuredContent}, e o texto serve a quem não tem a interface. */
    static CallToolResult vista(Supplier<?> leitura) {
        try {
            Object vista = leitura.get();
            Map<String, Object> estruturado = LEITURA.convertValue(vista, new TypeReference<Map<String, Object>>() {});
            return CallToolResult.builder().addTextContent(LEITURA.writeValueAsString(vista)).structuredContent(estruturado).build();
        } catch (ErroTipado erro) {
            return falha(erro);
        } catch (RuntimeException erro) {
            LOG.log(Logger.Level.ERROR, "Falha inesperada numa capacidade", erro);
            return falha(ErroTipado.de(CodigoErro.ERRO_INTERNO, "Erro interno. Tente de novo em instantes."));
        }
    }

    static <R> CallToolResult escrita(Supplier<ResultadoEscrita<R>> escrita) {
        try {
            ResultadoEscrita<R> resultado = escrita.get();
            Map<String, Object> saida = new LinkedHashMap<>();
            saida.put("resultado", resultado.valor());
            saida.put("operacao", resultado.simulado() ? null : operacao(resultado.operacao()));
            saida.put("repetido", resultado.repetido());
            saida.put("simulado", resultado.simulado());
            return CallToolResult.builder().addTextContent(JSON.writeValueAsString(saida)).build();
        } catch (ErroTipado erro) {
            return falha(erro);
        } catch (RuntimeException erro) {
            LOG.log(Logger.Level.ERROR, "Falha inesperada numa capacidade", erro);
            return falha(ErroTipado.de(CodigoErro.ERRO_INTERNO, "Erro interno. Tente de novo em instantes."));
        }
    }

    private static Map<String, Object> operacao(OperacaoRegistrada operacao) {
        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("id", operacao.referencia().id());
        saida.put("url", operacao.url());
        return saida;
    }

    private static CallToolResult falha(ErroTipado erro) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("codigo", erro.codigo().codigo());
        corpo.put("mensagem", erro.mensagem());
        corpo.put("campo", erro.campo());
        corpo.put("pode_repetir", erro.podeRepetir());
        corpo.put("candidatos", erro.candidatos());
        corpo.put("campos_faltando", erro.camposFaltando());
        corpo.put("sugestao", erro.sugestao());
        return CallToolResult.builder().isError(true).addTextContent(JSON.writeValueAsString(Map.of("erro", corpo))).build();
    }
}
