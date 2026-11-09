package tools.alicerce.financeiro.adaptador.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.alicerce.financeiro.aplicacao.Projetar;

/** Uma hipótese em {@code projetar}, com os campos achatados por tipo. A falta volta como {@code campos_faltando}. */
record HipoteseEntrada(
        @McpToolParam(description = "compra, novo_compromisso, mudanca_renda, mudanca_categoria ou aporte_meta. Obrigatório.", required = false) String tipo,
        @McpToolParam(description = "compra: o total; novo_compromisso: cada ocorrência; mudanca_renda: quanto muda por mês, negativo para perda; aporte_meta: quanto guardar por mês. Como \"3000.00\".", required = false) String valor,
        @JsonProperty("meio_pagamento")
        @McpToolParam(description = "compra: o meio, como em registrar_gasto (id, nome ou alias). No crédito, cada parcela vai para a fatura certa.", required = false) String meioPagamento,
        @McpToolParam(description = "compra no crédito ou novo_compromisso parcelado: em quantas parcelas.", required = false) Integer parcelas,
        @McpToolParam(description = "compra: o dia, AAAA-MM-DD, de hoje até o fim do horizonte; padrão: hoje.", required = false) String data,
        @McpToolParam(description = "novo_compromisso: saida (o dono paga) ou entrada (o dono recebe).", required = false) String direcao,
        @JsonProperty("tipo_compromisso")
        @McpToolParam(description = "novo_compromisso: recorrente (padrão), parcelado ou unico.", required = false) String tipoCompromisso,
        @McpToolParam(description = "novo_compromisso: mensal (padrão) ou anual.", required = false) String frequencia,
        @JsonProperty("primeiro_vencimento")
        @McpToolParam(description = "novo_compromisso: o primeiro vencimento, AAAA-MM-DD, de hoje em diante.", required = false) String primeiroVencimento,
        @McpToolParam(description = "novo_compromisso: a última data em que vence, AAAA-MM-DD.", required = false) String fim,
        @McpToolParam(description = "mudanca_categoria: a categoria (id, nome ou alias); as subcategorias mudam junto.", required = false) String categoria,
        @McpToolParam(description = "mudanca_categoria: quanto o gasto variável muda, em %, como \"-20\".", required = false) String percentual,
        @McpToolParam(description = "mudanca_renda, mudanca_categoria ou aporte_meta: o mês, AAAA-MM, em que começa; padrão: o próximo mês na renda, o atual na categoria e no aporte.", required = false) String de,
        @McpToolParam(description = "aporte_meta: a meta (id m-, nome ou alias).", required = false) String meta,
        @McpToolParam(description = "aporte_meta: o último mês com aporte, AAAA-MM; padrão: o fim do horizonte. Os aportes param quando a meta chega no alvo.", required = false) String ate) {

    Projetar.Hipotese hipotese() {
        return new Projetar.Hipotese(tipo, valor, meioPagamento, parcelas, data, direcao, tipoCompromisso, frequencia, primeiroVencimento, fim,
                categoria, percentual, de, meta, ate);
    }
}
