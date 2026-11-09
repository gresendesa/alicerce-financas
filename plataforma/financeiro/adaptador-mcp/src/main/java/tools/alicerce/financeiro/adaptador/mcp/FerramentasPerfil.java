package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.Declarar;
import tools.alicerce.financeiro.aplicacao.DefinirMeta;
import tools.alicerce.seguranca.EscopoExigido;

/** O perfil declarado: o que o dono pretende. */
@Component
class FerramentasPerfil {

    private final Declarar declarar;
    private final DefinirMeta definirMeta;

    FerramentasPerfil(Declarar declarar, DefinirMeta definirMeta) {
        this.declarar = declarar;
        this.definirMeta = definirMeta;
    }

    @McpTool(
            name = "declarar",
            title = "Declarar",
            description = """
                    Registra uma regra ou intenção do perfil do dono; a nova substitui a vigente do mesmo tipo, que fica guardada. \
                    Tipos: piso_periodo (saldo_minimo: com quanto, no mínimo, terminar cada mês, depois de pagas as contas do mês; \
                    "fechar no verde" é 0.01); categorias_essenciais (categorias: só entram num plano de cortes depois das outras, \
                    com as subcategorias); reserva_minima (valor, ou meses_de_gasto de 1 a 24: o saldo que o dono quer ter sempre; \
                    em meses, o servidor calcula o valor equivalente pelo gasto mensal típico); intencao_poupanca (percentual da \
                    renda, de 0 a 100; 0 é não poupar); teto_parcelas (percentual da renda, de 1 a 100, que as parcelas de um mês \
                    podem levar); meta_protegida (metas: só ativas e com data alvo; projetar aponta o cenário que atrasa uma delas, \
                    e situacao trata o atraso dela como crítico; cortes_necessarios não a lê); nota (texto \
                    livre, até 1000 caracteres, nas palavras do dono, sem acrescentar o que ele não disse; uma só: para acrescentar, reescreva a nota inteira). Listas (categorias, metas) \
                    substituem a anterior inteira: inclua as que já eram; vazia diz que nenhuma é. Envie só os campos do tipo. As \
                    vigentes aparecem em obter_contexto; situacao, projetar, ritmo_gastos e cortes_necessarios as usam. A saída traz \
                    a nova e a anterior; na reserva em meses, mostre ao dono o valor_equivalente e os meses de onde ele veio. \
                    Desfazer esta operação traz a anterior de volta. Gere uma chave_idempotencia por intenção do dono e \
                    reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:profile")
    public CallToolResult declarar(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "piso_periodo, categorias_essenciais, reserva_minima, intencao_poupanca, teto_parcelas, "
                    + "meta_protegida ou nota. Obrigatório.", required = false) String tipo,
            @McpToolParam(description = "Só piso_periodo: o saldo mínimo no fim do mês, como \"100.00\"; no verde é \"0.01\".", required = false) String saldo_minimo,
            @McpToolParam(description = "Só categorias_essenciais: a lista inteira, por id, nome ou alias.", required = false) List<String> categorias,
            @McpToolParam(description = "Só reserva_minima por valor: quanto ter sempre, como \"10000.00\".", required = false) String valor,
            @McpToolParam(description = "Só reserva_minima por meses: de 1 a 24 meses de gasto típico.", required = false) Integer meses_de_gasto,
            @McpToolParam(description = "Só intencao_poupanca (0 a 100) e teto_parcelas (1 a 100): o percentual da renda, como \"15\".", required = false) String percentual,
            @McpToolParam(description = "Só meta_protegida: a lista inteira, por id m-, nome ou alias.", required = false) List<String> metas,
            @McpToolParam(description = "Só nota: o texto inteiro, nas palavras do dono, até 1000 caracteres.", required = false) String texto,
            @McpToolParam(description = "Observação livre, como o motivo do dono.", required = false) String observacao) {
        return Respostas.escrita(() -> declarar.executar(Chamadores.de(contexto), new Declarar.Entrada(chave_idempotencia, lote, tipo,
                saldo_minimo, categorias, observacao, valor, meses_de_gasto, percentual, metas, texto)));
    }

    @McpTool(
            name = "definir_meta",
            title = "Definir meta",
            description = """
                    Cria ou altera uma meta de valor: juntar um valor, de preferência até uma data ("R$ 6.000 para a viagem até \
                    dezembro de 2027"). A meta não guarda dinheiro: o progresso é a soma dos aportes menos os resgates, registrados \
                    com registrar_transferencia e meta. A saída traz o progresso, quanto falta, o percentual e, com data_alvo, \
                    aporte_mensal_necessario: o que falta dividido pelos meses de hoje até a data, contando os dois. Todos os \
                    números vêm do servidor: repasse-os como vieram, nunca recalcule. conta_reservada é a conta (tipo reserva ou \
                    poupança) separada para a meta: fica fora do saldo disponível de projetar e de cortes_necessarios, e um aporte \
                    entra nela. Para simular "se eu guardar R$ 500 por mês, chego lá?", use projetar com a hipótese aporte_meta. Para \
                    alterar, envie ref e só o que muda; texto vazio em data_alvo ou conta_reservada tira o valor. situacao concluida \
                    ou encerrada tira a meta das contas do futuro. Desfazer a criação arquiva a meta; com aportes registrados, \
                    desfaça-os antes. Cite o id m- e a URL. Gere uma chave_idempotencia por intenção do dono e reutilize-a se \
                    repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:profile")
    public CallToolResult definirMeta(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "Para alterar: id m-, nome ou alias da meta. Ausente cria uma nova.", required = false) String ref,
            @McpToolParam(description = "Nome curto, como \"Viagem 2027\". Obrigatório ao criar.", required = false) String nome,
            @McpToolParam(description = "Outros nomes que o dono usa.", required = false) List<String> aliases,
            @McpToolParam(description = "Quanto juntar, como \"6000.00\". Obrigatório ao criar.", required = false) String valor_alvo,
            @McpToolParam(description = "Até quando, AAAA-MM-DD, de hoje em diante; ao alterar, \"\" tira a data.", required = false) String data_alvo,
            @McpToolParam(description = "De 1 (a mais importante) a 5; padrão: 3.", required = false) Integer prioridade,
            @McpToolParam(description = "Conta reserva ou poupança separada para a meta (id, nome ou alias); ao alterar, \"\" tira.", required = false) String conta_reservada,
            @McpToolParam(description = "ativa (padrão), concluida ou encerrada.", required = false) String situacao,
            @McpToolParam(description = "Observação livre.", required = false) String observacao) {
        return Respostas.escrita(() -> definirMeta.executar(Chamadores.de(contexto), new DefinirMeta.Entrada(chave_idempotencia, lote, ref, nome,
                aliases, valor_alvo, data_alvo, prioridade, conta_reservada, situacao, observacao)));
    }
}
