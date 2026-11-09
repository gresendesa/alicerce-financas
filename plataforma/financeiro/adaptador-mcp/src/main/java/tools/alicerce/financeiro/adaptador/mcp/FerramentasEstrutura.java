package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.seguranca.EscopoExigido;

@Component
class FerramentasEstrutura {

    private final DefinirConta definirConta;
    private final DefinirMeioPagamento definirMeio;
    private final DefinirAmbito definirAmbito;

    FerramentasEstrutura(DefinirConta definirConta, DefinirMeioPagamento definirMeio, DefinirAmbito definirAmbito) {
        this.definirConta = definirConta;
        this.definirMeio = definirMeio;
        this.definirAmbito = definirAmbito;
    }

    @McpTool(
            name = "definir_conta",
            title = "Definir conta ou categoria",
            description = """
                    Cria ou altera uma conta, cartão, categoria ou fonte de renda. Sem ref, cria; com ref, altera ou arquiva. \
                    Cartão usa tipo cartao_credito e exige cartao com dia_fechamento e dia_vencimento; criar um cartão cria \
                    também o meio de pagamento de crédito dele, com o mesmo nome. Em cartão, saldo_abertura é a dívida atual e \
                    entra na próxima fatura a vencer. Dias novos só valem para faturas que ainda não existem. data_abertura é \
                    o marco da conta, a data desde quando o sistema responde pelo saldo, e não muda depois; ausente, é hoje. Para acertar o \
                    valor da abertura, use corrigir_lancamento em lancamento_abertura; ela nunca se ajusta aqui. Conta de dinheiro pode \
                    ficar em outra moeda de duas casas, como USD ou EUR: informe moeda, e saldo_abertura e saldo saem nela. A moeda não \
                    muda depois do primeiro movimento. Conta em outra moeda só recebe e envia transferências. Gere uma \
                    chave_idempotencia por intenção do dono e reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:structure")
    public CallToolResult definirConta(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "A conta a alterar: id, nome ou alias. Ausente = criar.", required = false) String ref,
            @McpToolParam(description = "corrente, poupanca, carteira, investimento, reserva, cartao_credito, emprestimo, categoria ou fonte_renda. Obrigatório ao criar.", required = false) String tipo,
            @McpToolParam(description = "Nome da conta. Obrigatório ao criar.", required = false) String nome,
            @McpToolParam(description = "Outros nomes pelos quais o dono chama a conta.", required = false) List<String> aliases,
            @McpToolParam(description = "Nome do banco; criado se não existir.", required = false) String instituicao,
            @McpToolParam(description = "Categoria-mãe, para categorias.", required = false) String conta_pai,
            @McpToolParam(description = "Saldo inicial na moeda da conta, como \"1234.56\". Só ao criar.", required = false) String saldo_abertura,
            @McpToolParam(description = "Data do saldo e marco da conta, AAAA-MM-DD; padrão: hoje. Só ao criar.", required = false) String data_abertura,
            @McpToolParam(description = "true arquiva; false desarquiva.", required = false) Boolean arquivar,
            @McpToolParam(description = "Dados do cartão, em tipo cartao_credito.", required = false) CartaoEntrada cartao,
            @McpToolParam(description = "Moeda da conta, ISO 4217, como USD; padrão: a do dono. Só em conta de dinheiro.", required = false) String moeda) {
        return Respostas.escrita(() -> definirConta.executar(Chamadores.de(contexto), new DefinirConta.Entrada(
                chave_idempotencia, lote, ref, tipo, nome, aliases, instituicao, conta_pai, saldo_abertura, data_abertura,
                arquivar, cartao == null ? null : cartao.dados(), moeda)));
    }

    @McpTool(
            name = "definir_meio_pagamento",
            title = "Definir meio de pagamento",
            description = """
                    Cria ou altera um meio de pagamento: pix, débito, crédito, dinheiro, transferência ou boleto, ligado à conta \
                    de onde o dinheiro sai. Crédito liga a um cartão, e o cartão já nasce com o seu. Sem ref, cria; com ref, altera ou arquiva. Gere \
                    uma chave_idempotencia por intenção do dono.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:structure")
    public CallToolResult definirMeioPagamento(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "O meio a alterar: id, nome ou alias. Ausente = criar.", required = false) String ref,
            @McpToolParam(description = "pix, debito, credito, dinheiro, transferencia ou boleto. Obrigatório ao criar.", required = false) String tipo,
            @McpToolParam(description = "Nome do meio, como \"Pix Itaú\". Obrigatório ao criar.", required = false) String nome,
            @McpToolParam(description = "Outros nomes pelos quais o dono chama o meio.", required = false) List<String> aliases,
            @McpToolParam(description = "A conta de onde o dinheiro sai. Obrigatória ao criar.", required = false) String conta,
            @McpToolParam(description = "true torna este o padrão do seu tipo.", required = false) Boolean padrao,
            @McpToolParam(description = "true arquiva; false desarquiva.", required = false) Boolean arquivar) {
        return Respostas.escrita(() -> definirMeio.executar(Chamadores.de(contexto), new DefinirMeioPagamento.Entrada(
                chave_idempotencia, lote, ref, tipo, nome, aliases, conta, padrao, arquivar)));
    }

    @McpTool(
            name = "definir_ambito",
            title = "Definir âmbito",
            description = """
                    Cria, altera ou arquiva um âmbito (para quem é o gasto: pessoal, família, igreja) e define âmbitos \
                    padrão. O primeiro âmbito criado é o padrão do dono. Gere uma chave_idempotencia por intenção do dono.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:structure")
    public CallToolResult definirAmbito(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "O âmbito a alterar: id, nome ou alias. Ausente = criar.", required = false) String ref,
            @McpToolParam(description = "Nome do âmbito. Obrigatório ao criar.", required = false) String nome,
            @McpToolParam(description = "Outros nomes pelos quais o dono chama o âmbito.", required = false) List<String> aliases,
            @McpToolParam(description = "true torna este o âmbito padrão do dono.", required = false) Boolean padrao,
            @McpToolParam(description = "true arquiva; false desarquiva.", required = false) Boolean arquivar,
            @McpToolParam(description = "Meio de pagamento que passa a usar este âmbito por padrão.", required = false) String padrao_do_meio) {
        return Respostas.escrita(() -> definirAmbito.executar(Chamadores.de(contexto), new DefinirAmbito.Entrada(
                chave_idempotencia, lote, ref, nome, aliases, padrao, arquivar, padrao_do_meio)));
    }
}
