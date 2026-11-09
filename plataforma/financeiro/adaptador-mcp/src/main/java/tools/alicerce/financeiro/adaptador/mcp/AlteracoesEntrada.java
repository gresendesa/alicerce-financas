package tools.alicerce.financeiro.adaptador.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;

/** O que muda em {@code corrigir_lancamento}. */
record AlteracoesEntrada(
        @McpToolParam(description = "Valor certo, como \"45.90\", na moeda do lançamento. Não vale para lançamento com itens.", required = false) String valor,
        @McpToolParam(description = "Descrição certa.", required = false) String descricao,
        @McpToolParam(description = "Data certa, AAAA-MM-DD.", required = false) String data,
        @McpToolParam(description = "Categoria certa. Não vale para lançamento com itens.", required = false) String categoria,
        @McpToolParam(description = "Âmbito certo. Não vale para lançamento com itens.", required = false) String ambito,
        @JsonProperty("meio_pagamento")
        @McpToolParam(description = "Meio de pagamento certo, em gastos.", required = false) String meioPagamento,
        @McpToolParam(description = "Conta certa, em ganhos.", required = false) String conta,
        @McpToolParam(description = "Contraparte certa.", required = false) String contraparte,
        @McpToolParam(description = "Observação certa.", required = false) String observacao,
        @JsonProperty("valor_original")
        @McpToolParam(description = "Valor certo na moeda original. Num gasto ou ganho, mantém a cotação e recalcula o valor; num câmbio, mantém o valor e recalcula a cotação. Envie só o que o dono corrigiu: o servidor calcula o resto.", required = false) String valorOriginal,
        @JsonProperty("moeda_original")
        @McpToolParam(description = "Moeda original certa, ISO 4217. Exigida se o lançamento ainda não tem moeda original.", required = false) String moedaOriginal,
        @McpToolParam(description = "Cotação certa. Sem valor, recalcula o valor.", required = false) String cotacao,
        @JsonProperty("data_cotacao")
        @McpToolParam(description = "Data certa da cotação, AAAA-MM-DD.", required = false) String dataCotacao,
        @McpToolParam(description = "Número certo de parcelas, de 1 a 48. Só no crédito.", required = false) Integer parcelas,
        @JsonProperty("total_com_juros")
        @McpToolParam(description = "Total certo com juros. Em compra com juros, valor é o preço à vista.", required = false) String totalComJuros,
        @JsonProperty("parcela_na_abertura")
        @McpToolParam(description = "Em compra em andamento, se a parcela já está dentro do saldo de abertura da fatura. Ausente mantém a resposta anterior.", required = false) Boolean parcelaNaAbertura) {

    CorrigirLancamento.Alteracoes alteracoes() {
        return new CorrigirLancamento.Alteracoes(valor, descricao, data, categoria, ambito, meioPagamento, conta, contraparte, observacao,
                new EntradaMoeda(valorOriginal, moedaOriginal, cotacao, dataCotacao), parcelas, totalComJuros, parcelaNaAbertura);
    }
}
