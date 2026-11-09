package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.seguranca.EscopoExigido;

@Component
class FerramentasOperacoes {

    private final ListarOperacoes listarOperacoes;
    private final Desfazer desfazer;

    FerramentasOperacoes(ListarOperacoes listarOperacoes, Desfazer desfazer) {
        this.listarOperacoes = listarOperacoes;
        this.desfazer = desfazer;
    }

    @McpTool(
            name = "listar_operacoes",
            title = "Listar operações",
            description = """
                    O histórico das escritas, para o dono escolher o que desfazer. Sem filtros, as 20 mais recentes \
                    ainda aplicadas. Cada operação traz id curto, URL, um resumo legível, lote, cliente, horário e estado.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult listarOperacoes(McpTransportContext contexto,
            @McpToolParam(description = "Só as operações deste lote.", required = false) String lote,
            @McpToolParam(description = "Só as deste assistente, como claude-ai.", required = false) String cliente,
            @McpToolParam(description = "Data inicial, AAAA-MM-DD.", required = false) String de,
            @McpToolParam(description = "Data final, AAAA-MM-DD, inclusive.", required = false) String ate,
            @McpToolParam(description = "Só as desta capacidade, como registrar_gasto.", required = false) String capacidade,
            @McpToolParam(description = "true inclui as já desfeitas.", required = false) Boolean incluir_desfeitas,
            @McpToolParam(description = "O proximo_cursor da página anterior.", required = false) String cursor) {
        return Respostas.leitura(() -> listarOperacoes.executar(Chamadores.de(contexto), new ListarOperacoes.Entrada(
                lote, cliente, de, ate, capacidade, incluir_desfeitas, cursor)));
    }

    @McpTool(
            name = "desfazer",
            title = "Desfazer",
            description = """
                    Reverte uma operação ou um lote inteiro, em dois passos. Primeiro passo, sem confirmacao: nada muda, e \
                    volta a prévia, com um token válido por 10 minutos. Mostre a prévia ao dono. Segundo passo, depois do sim dele: chame esta \
                    mesma tool de novo, com o mesmo alvo ou lote, o token em confirmacao e uma chave_idempotencia. O segundo passo é o \
                    que aplica; não repita a prévia sem o token nem peça outra. Desfazer nunca apaga: lançamentos são \
                    estornados e estruturas voltam à versão anterior. Pagamento só bloqueia se sobrar na fatura. Desfazer um desfazer refaz o que foi desfeito. \
                    Numa declaração do perfil, declaracoes_revertidas mostra a que sai e a que volta a valer; mostre as duas ao dono. \
                    Exige os mesmos escopos das operações revertidas.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult desfazer(McpTransportContext contexto,
            @McpToolParam(description = "Id curto ou URL da operação. Informe este ou lote.", required = false) String alvo,
            @McpToolParam(description = "Lote inteiro a desfazer. Informe este ou alvo.", required = false) String lote,
            @McpToolParam(description = "O token devolvido pela prévia, depois do sim do dono.", required = false) String confirmacao,
            @McpToolParam(description = "Chave única desta intenção. Obrigatória com confirmacao.", required = false) String chave_idempotencia) {
        return Respostas.escrita(() -> desfazer.executar(Chamadores.de(contexto),
                new Desfazer.Entrada(chave_idempotencia, alvo, lote, confirmacao)));
    }
}
