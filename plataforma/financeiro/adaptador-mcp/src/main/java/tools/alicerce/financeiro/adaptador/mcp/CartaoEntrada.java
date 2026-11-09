package tools.alicerce.financeiro.adaptador.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.alicerce.financeiro.aplicacao.DefinirConta;

/** Os dados do cartão em {@code definir_conta}. */
record CartaoEntrada(
        @JsonProperty("dia_fechamento")
        @McpToolParam(description = "Dia em que a fatura fecha, de 1 a 31. Obrigatório ao criar.", required = false) Integer diaFechamento,
        @JsonProperty("dia_vencimento")
        @McpToolParam(description = "Dia em que a fatura vence, de 1 a 31. Obrigatório ao criar.", required = false) Integer diaVencimento,
        @McpToolParam(description = "Limite total, como \"5000.00\".", required = false) String limite,
        @JsonProperty("conta_pagamento")
        @McpToolParam(description = "Conta de onde a fatura costuma ser paga.", required = false) String contaPagamento) {

    DefinirConta.DadosCartao dados() {
        return new DefinirConta.DadosCartao(diaFechamento, diaVencimento, limite, contaPagamento);
    }
}
