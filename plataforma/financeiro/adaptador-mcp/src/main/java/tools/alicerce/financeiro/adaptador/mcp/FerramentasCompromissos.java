package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.ListarCompromissos;
import tools.alicerce.financeiro.aplicacao.ObterAgenda;
import tools.alicerce.seguranca.EscopoExigido;

/** Compromissos a pagar e a receber: definir, listar e a agenda do que vence. */
@Component
class FerramentasCompromissos {

    private final DefinirCompromisso definirCompromisso;
    private final ListarCompromissos listarCompromissos;
    private final ObterAgenda obterAgenda;

    FerramentasCompromissos(DefinirCompromisso definirCompromisso, ListarCompromissos listarCompromissos, ObterAgenda obterAgenda) {
        this.definirCompromisso = definirCompromisso;
        this.listarCompromissos = listarCompromissos;
        this.obterAgenda = obterAgenda;
    }

    @McpTool(
            name = "definir_compromisso",
            title = "Definir compromisso",
            description = """
                    Cria, altera ou encerra um compromisso: algo combinado para pagar (saida) ou receber (entrada). recorrente \
                    se repete todo mês ou ano até ser encerrado (aluguel, salário, assinatura); parcelado tem um número fixo de \
                    parcelas mensais (parcelamento de tributo, dívida com alguém, uso do cartão de outra pessoa); unico vence uma \
                    vez. O compromisso nunca gera lançamento: o pagamento é registrar_gasto ou registrar_ganho com compromisso. \
                    Compra parcelada no seu cartão não é compromisso: as parcelas já estão nas faturas. referencia guarda o número \
                    do parcelamento ou um protocolo, para o dono achar no banco ou no órgão: NUNCA guarde ali, nem em observacao, \
                    senha, PIN, código de segurança, token ou qualquer credencial; se o dono ditar uma, recuse e explique. Parcelamento \
                    que já estava em andamento: envie o primeiro vencimento real, o total de parcelas e, em parcelas_pagas_antes, \
                    quantas já foram pagas fora do sistema. Para alterar, envie ref e só o que muda; encerrar true tira do ar o que \
                    venceria depois de hoje (ou de fim). Desfazer a criação apaga o compromisso da agenda. Gere uma \
                    chave_idempotencia por intenção do dono e reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:profile")
    public CallToolResult definirCompromisso(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "Para alterar: id k-, nome ou alias do compromisso. Ausente cria um novo.", required = false) String ref,
            @McpToolParam(description = "saida (o dono paga) ou entrada (o dono recebe). Obrigatória ao criar.", required = false) String direcao,
            @McpToolParam(description = "recorrente, parcelado ou unico. Obrigatório ao criar.", required = false) String tipo,
            @McpToolParam(description = "Nome curto, como \"Parcelamento IPTU 2026\". Obrigatório ao criar.", required = false) String nome,
            @McpToolParam(description = "Outros nomes que o dono usa.", required = false) List<String> aliases,
            @McpToolParam(description = "Valor de cada ocorrência ou parcela, como \"150.00\". Obrigatório ao criar, salvo com valor_total.", required = false) String valor,
            @McpToolParam(description = "Só parcelado: o total, no lugar de valor; a sobra de centavos vai na primeira parcela.", required = false) String valor_total,
            @McpToolParam(description = "Só parcelado: quantas parcelas no total, de 1 a 480.", required = false) Integer parcelas,
            @McpToolParam(description = "Só parcelado: quantas das primeiras parcelas já foram pagas antes; padrão: 0.", required = false) Integer parcelas_pagas_antes,
            @McpToolParam(description = "mensal (padrão) ou anual. Não vale em unico.", required = false) String frequencia,
            @McpToolParam(description = "Data do primeiro vencimento, AAAA-MM-DD; os seguintes caem no mesmo dia. Obrigatória ao criar.", required = false) String primeiro_vencimento,
            @McpToolParam(description = "Última data em que pode vencer, AAAA-MM-DD; ausente em recorrente sem fim.", required = false) String fim,
            @McpToolParam(description = "Com quem é o compromisso: quem recebe ou quem paga.", required = false) String contraparte,
            @McpToolParam(description = "Número do parcelamento ou protocolo. Nunca senha ou credencial.", required = false) String referencia,
            @McpToolParam(description = "Categoria (saída) ou fonte de renda (entrada) sugerida para o pagamento.", required = false) String categoria,
            @McpToolParam(description = "Conta de onde sai ou onde entra o dinheiro.", required = false) String conta,
            @McpToolParam(description = "Meio de pagamento usual. Crédito não vale em parcelado.", required = false) String meio_pagamento,
            @McpToolParam(description = "Âmbito, como Família.", required = false) String ambito,
            @McpToolParam(description = "Observação livre. Nunca senha ou credencial.", required = false) String observacao,
            @McpToolParam(description = "true encerra; false reabre.", required = false) Boolean encerrar) {
        return Respostas.escrita(() -> definirCompromisso.executar(Chamadores.de(contexto), new DefinirCompromisso.Entrada(chave_idempotencia, lote,
                ref, direcao, tipo, nome, aliases, valor, valor_total, parcelas, parcelas_pagas_antes, frequencia, primeiro_vencimento, fim,
                contraparte, referencia, categoria, conta, meio_pagamento, ambito, observacao, encerrar)));
    }

    @McpTool(
            name = "listar_compromissos",
            title = "Listar compromissos",
            description = """
                    Os compromissos do dono, com referência (número do parcelamento ou protocolo), contraparte, parcelas pagas, \
                    em aberto e atrasadas, valor pago, valor restante e a próxima ocorrência. Responde perguntas como "quais são os \
                    números dos meus parcelamentos?" e "quanto ainda devo para a Ana?". Todos os números vêm do servidor: repasse-os \
                    como vieram, sem somar. Num recorrente sem fim, valor_restante e total não existem. Parcela paga só em parte \
                    continua em aberto: valor_restante já desconta o pago, saldo.pagas_em_parte conta quantas são, e na ocorrência \
                    falta_na_ocorrencia diz quanto falta dela e pagamentos lista cada pagamento. Cite o id k- e a URL de cada \
                    compromisso; resolver_referencia com o id traz todas as ocorrências.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult listarCompromissos(McpTransportContext contexto,
            @McpToolParam(description = "Procura no nome, nos aliases, na referência, na contraparte e na observação.", required = false) String texto,
            @McpToolParam(description = "Procura na contraparte: quem recebe ou quem paga.", required = false) String pessoa,
            @McpToolParam(description = "Só este âmbito: id, nome ou alias.", required = false) String ambito,
            @McpToolParam(description = "saida ou entrada; ausente traz os dois.", required = false) String direcao,
            @McpToolParam(description = "true inclui os encerrados.", required = false) Boolean incluir_encerrados) {
        return Respostas.leitura(() -> listarCompromissos.executar(Chamadores.de(contexto), new ListarCompromissos.Entrada(texto, pessoa, ambito,
                direcao, incluir_encerrados)));
    }

    @McpTool(
            name = "obter_agenda",
            title = "Obter agenda",
            description = """
                    O que está atrasado e o que vence num período (padrão: de hoje a 30 dias), a pagar e a receber: ocorrências \
                    de compromisso em aberto e faturas de cartão, cada uma pelo que falta pagar (valor). Parcela paga em parte \
                    aparece pelo restante, com valor_previsto e valor_pago. Uma compra parcelada no cartão aparece só \
                    dentro da fatura, nunca também como compromisso. Os totais vêm do servidor e já somam tudo: repasse-os como \
                    vieram, nunca some itens nem totais. conferencias_vencidas lista as contas sem conferência há mais de 30 dias. \
                    Feita para tarefas agendadas: chame sem argumentos e resuma ao dono o que está atrasado e o que vence logo, com \
                    os links.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult obterAgenda(McpTransportContext contexto,
            @McpToolParam(description = "Data inicial, AAAA-MM-DD; padrão: hoje. Atrasados vêm sempre.", required = false) String de,
            @McpToolParam(description = "Data final, AAAA-MM-DD, inclusive; padrão: 30 dias depois.", required = false) String ate,
            @McpToolParam(description = "Mês, semana ISO ou ano, no lugar de de e ate: 2026-11, 2026-W46 ou 2026.", required = false) String periodo) {
        return Respostas.leitura(() -> obterAgenda.executar(Chamadores.de(contexto), new ObterAgenda.Entrada(de, ate, periodo)));
    }
}
