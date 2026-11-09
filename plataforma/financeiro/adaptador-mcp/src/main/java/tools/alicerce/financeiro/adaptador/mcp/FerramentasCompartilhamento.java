package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.PrestarContas;
import tools.alicerce.seguranca.EscopoExigido;

/** Compartilhamento: a prestação de contas de um âmbito num link que abre sem login. */
@Component
class FerramentasCompartilhamento {

    private final PrestarContas prestarContas;

    FerramentasCompartilhamento(PrestarContas prestarContas) {
        this.prestarContas = prestarContas;
    }

    @McpTool(
            name = "prestar_contas",
            title = "Prestar contas de um âmbito",
            description = """
                    Publica a prestação de contas de um âmbito num período (por exemplo, o que o dono gastou com a família no mês) \
                    numa página que abre SEM LOGIN para quem tiver a URL. Antes de o dono enviar a URL, diga sempre que ela abre \
                    sem login e o que mostra: os totais de gastos e entradas do âmbito, a quebra por categoria e cada lançamento \
                    (data, descrição, estabelecimento, tipo do meio e valor) e, com incluir_comprovantes, as imagens de \
                    comprovante e as chaves das NFC-e; fotos ficam fora. Nunca mostra saldos, contas, nomes de meios nem outros \
                    âmbitos. Na saída, o_que_mostra diz isso para repassar; gastos, entradas e quantidade_lancamentos são os \
                    números da página: repasse-os como vieram. O link vale valida_ate (padrão 90 dias, máximo 365). A página é um \
                    retrato (s-): corrigir um lançamento depois não a muda; para isso, gere um link novo e revogue o antigo. Para \
                    revogar, chame só com revogar e a URL do link ou o id s- do retrato; desfazer a criação também fecha o link. \
                    Repetir a chamada cria outro link. Exige fin:share: se vier insufficient_scope, peça ao dono para reconectar o \
                    conector do Alicerce.""",
            // openWorldHint false, como anexar_comprovante: o link é do próprio Alicerce. Com true, o ChatGPT bloqueou a chamada
            // antes de ela chegar ao servidor. O aviso de que abre sem login fica na descrição.
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false,
                    openWorldHint = false))
    @EscopoExigido("fin:share")
    public CallToolResult prestarContas(McpTransportContext contexto,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "O âmbito: id, nome ou alias, como família. Obrigatório para criar.", required = false) String ambito,
            @McpToolParam(description = "Data inicial, AAAA-MM-DD. Use com ate, ou use periodo.", required = false) String de,
            @McpToolParam(description = "Data final, AAAA-MM-DD, inclusive.", required = false) String ate,
            @McpToolParam(description = "Mês, semana ISO ou ano: 2026-09, 2026-W38 ou 2026. No lugar de de e ate.", required = false) String periodo,
            @McpToolParam(description = "true mostra as imagens de comprovante e as chaves das NFC-e; padrão: false.", required = false) Boolean incluir_comprovantes,
            @McpToolParam(description = "Por quantos dias o link abre, de 1 a 365; padrão: 90.", required = false) Integer validade_dias,
            @McpToolParam(description = "Para revogar: a URL do link ou o id s- do retrato dele. Sozinho, sem os outros campos.", required = false) String revogar) {
        return Respostas.escrita(() -> prestarContas.executar(Chamadores.de(contexto), new PrestarContas.Entrada(lote, ambito, de, ate, periodo,
                incluir_comprovantes, validade_dias, revogar)));
    }
}
