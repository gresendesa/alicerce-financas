package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.ListarLancamentos;
import tools.alicerce.financeiro.aplicacao.ObterFatura;
import tools.alicerce.seguranca.EscopoExigido;

@Component
class FerramentasConsulta {

    private final ListarLancamentos listarLancamentos;
    private final ObterFatura obterFatura;

    FerramentasConsulta(ListarLancamentos listarLancamentos, ObterFatura obterFatura) {
        this.listarLancamentos = listarLancamentos;
        this.obterFatura = obterFatura;
    }

    @McpTool(
            name = "obter_fatura",
            title = "Obter fatura",
            description = """
                    Mostra uma fatura de cartão: itens, com as parcelas marcadas n/N, total, pago, restante, fechamento, \
                    vencimento e situação (aberta, fechada, parcialmente_paga ou paga). Em fatura com saldo de abertura, abertura mostra \
                    quanto dele já tem compra ligada: é detalhe de um item, já dentro de total. Sem periodo, é a fatura em que uma compra \
                    de hoje entra. Em cada item, valor_na_fatura é só o que cai nesta fatura; total já é a soma deles. Repasse \
                    total como veio e nunca o some com o valor das compras: o restante de uma compra parcelada está nas \
                    outras faturas. Cite o id curto e a URL ao dono: ela abre a tela no navegador.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult obterFatura(McpTransportContext contexto,
            @McpToolParam(description = "Cartão: id, nome ou alias. Obrigatório.", required = false) String cartao,
            @McpToolParam(description = "Mês do vencimento, como 2026-11, ou o id f-.", required = false) String periodo) {
        return Respostas.leitura(() -> obterFatura.executar(Chamadores.de(contexto), cartao, periodo));
    }

    @McpTool(
            name = "listar_lancamentos",
            title = "Listar lançamentos",
            description = """
                    Lista lançamentos, dos mais recentes aos mais antigos, até 50 por página. Passe proximo_cursor em \
                    cursor para a página seguinte. Um lançamento anulado e seu estorno ficam de fora; incluir_anulados \
                    mostra o histórico completo, e o campo anulado diz se cada um vale. Cite o id curto e a URL ao dono: ela \
                    abre a tela no navegador. O campo valor é o que vale, na moeda do campo moeda: nunca \
                    some valores de moedas diferentes. original só descreve uma compra em outra moeda, ou o outro lado de um câmbio, e \
                    nunca entra em soma. Cada movimento traz sua moeda, e num câmbio eles passam pelas contas técnicas de conversão. Numa compra parcelada, valor é \
                    a compra inteira, e os movimentos marcados n/N já estão dentro dele. procedencia diz se o fato nasceu no sistema \
                    (endogeno) ou veio de fora (exogeno): um exógeno vale como gasto ou ganho, mas não mexeu no saldo de conta_historica.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult listarLancamentos(McpTransportContext contexto,
            @McpToolParam(description = "Data inicial, AAAA-MM-DD.", required = false) String de,
            @McpToolParam(description = "Data final, AAAA-MM-DD, inclusive.", required = false) String ate,
            @McpToolParam(description = "Conta: id, nome ou alias.", required = false) String conta,
            @McpToolParam(description = "Categoria ou fonte de renda; inclui as subcategorias. Prevalece sobre conta.", required = false) String categoria,
            @McpToolParam(description = "Meio de pagamento: id, nome ou alias.", required = false) String meio_pagamento,
            @McpToolParam(description = "Trecho da descrição, contraparte ou observação.", required = false) String texto,
            @McpToolParam(description = "Valor mínimo do campo valor, como \"10.00\", na moeda de cada lançamento.", required = false) String valor_minimo,
            @McpToolParam(description = "Valor máximo do campo valor, como \"100.00\", na moeda de cada lançamento.", required = false) String valor_maximo,
            @McpToolParam(description = "true inclui lançamentos anulados e estornos.", required = false) Boolean incluir_anulados,
            @McpToolParam(description = "O proximo_cursor da página anterior.", required = false) String cursor,
            @McpToolParam(description = "Só endogeno ou só exogeno.", required = false) String procedencia) {
        return Respostas.leitura(() -> listarLancamentos.executar(Chamadores.de(contexto), new ListarLancamentos.Entrada(
                de, ate, conta, categoria, meio_pagamento, texto, valor_minimo, valor_maximo, incluir_anulados, cursor, procedencia)));
    }
}
