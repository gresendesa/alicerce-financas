package tools.alicerce.financeiro.adaptador.mcp;

import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;

/** Uma linha de nota em {@code registrar_gasto}. Campos opcionais no esquema: a falta volta como {@code campos_faltando}. */
record ItemEntrada(
        @McpToolParam(description = "Nome do item. Obrigatório.", required = false) String descricao,
        @McpToolParam(description = "Quantidade, como \"1.5\".", required = false) String quantidade,
        @McpToolParam(description = "Valor total do item, como \"12.90\". Obrigatório.", required = false) String valor,
        @McpToolParam(description = "Categoria do item; ausente = a do gasto.", required = false) String categoria,
        @McpToolParam(description = "Âmbito do item; ausente = o do gasto.", required = false) String ambito) {

    RegistrarGasto.Item item() {
        return new RegistrarGasto.Item(descricao, quantidade, valor, categoria, ambito);
    }
}
