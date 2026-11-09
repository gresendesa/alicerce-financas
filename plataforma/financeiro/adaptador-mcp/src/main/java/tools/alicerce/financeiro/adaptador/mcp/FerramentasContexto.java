package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.seguranca.EscopoExigido;

@Component
class FerramentasContexto {

    private final ObterContexto obterContexto;
    private final ResolverReferencia resolverReferencia;

    FerramentasContexto(ObterContexto obterContexto, ResolverReferencia resolverReferencia) {
        this.obterContexto = obterContexto;
        this.resolverReferencia = resolverReferencia;
    }

    @McpTool(
            name = "obter_contexto",
            title = "Obter contexto financeiro",
            description = """
                    Devolve, numa chamada, tudo o que é preciso saber antes de qualquer outra coisa: fuso e moeda \
                    do dono, contas e categorias, cartões, meios de pagamento, âmbitos, declarações, metas e \
                    compromissos. Cada conta traz sua moeda, e o saldo está nela: nunca some saldos de moedas diferentes, nem \
                    os trate como reais; para um total, use resumo_periodo, que converte com a cotação à vista. Em cada conta, marco é a data desde quando o sistema responde pelo saldo: fato anterior a ele \
                    entra por inteiro, sem mudar o saldo. conta_conectada diz qual login do Alicerce respondeu; com conta_de_teste, \
                    os dados são fictícios: avise o dono e nunca os trate como a vida real dele. Cada conta com saldo traz a última \
                    conferência e se ela está vencida (mais de 30 dias). metas traz cada meta com progresso (aportes menos resgates), \
                    falta e aporte_mensal_necessario até a data alvo, prontos: repasse-os, nunca recalcule. declaracoes traz a regra \
                    vigente de cada tipo de declarar; a reserva em meses vem com valor_equivalente e os meses do gasto típico, e a nota \
                    é o texto que o dono pediu para guardar: leia-a antes de responder. compromissos traz os ativos com o próximo vencimento; para \
                    referências, parcelas pagas e valor restante, use listar_compromissos. Chame no início de cada conversa, junto com guia.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public SaidaContexto obterContexto(McpTransportContext contexto) {
        return SaidaContexto.de(obterContexto.executar(Chamadores.de(contexto)));
    }

    @McpTool(
            name = "guia",
            title = "Guia do Alicerce",
            description = """
                    O guia do Alicerce para o assistente: o que é, os conceitos (livro-razão imutável, contas, marco, visões \
                    competência e caixa, retratos, desfazer, compromissos e âmbitos), o que é cada prefixo de id curto (t-, a-, \
                    s-...), qual tool usar para cada pergunta e as boas práticas. Chame uma vez por conversa, antes da primeira \
                    resposta sobre o Alicerce. Chame sempre, antes de responder de cabeça, quando o dono perguntar o que é um \
                    termo como marco, âmbito, retrato, competência, caixa, compromisso, conferência, lote ou desfazer: nesta \
                    conversa, o termo é do Alicerce e tem o sentido do guia, não o sentido comum.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult guia(McpTransportContext contexto) {
        return CallToolResult.builder().addTextContent(Guia.TEXTO).build();
    }

    @McpTool(
            name = "resolver_referencia",
            title = "Resolver referência",
            description = """
                    Resolve qualquer id curto ou URL colada na conversa; o prefixo diz o que é. Um lançamento (t-) vem com movimentos, itens, origem e \
                    a operação que o registrou; uma conta (a-), com tipo, moeda, saldo, marco e última conferência, como em obter_contexto; \
                    uma fatura (f-), como em obter_fatura; um compromisso (k-), com todas as ocorrências; um comprovante (n-), com o link \
                    temporário; uma operação (o-), como em listar_operacoes; um retrato (s-), com os parâmetros \
                    e o resultado exatos daquele momento; uma meta (m-), com progresso, quanto falta, aporte mensal necessário e cada \
                    aporte e resgate. Para saber se um retrato ainda vale, use comparar_agora: a \
                    saída traz a diferença para o cálculo de hoje (numa projeção, o saldo final de cada mês da linha de base). Aceita fin:t-8K3QZ2, t-8K3QZ2 ou a URL completa. Chame sempre que \
                    aparecer na conversa um id nesse formato (letra, hífen e seis caracteres, como a-MY9RNB) ou uma URL do Alicerce, \
                    mesmo que ele já tenha aparecido antes: responda pelo que o servidor devolve agora, nunca pela memória.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult resolverReferencia(McpTransportContext contexto,
            @McpToolParam(description = "Id curto ou URL. Obrigatória.", required = false) String ref,
            @McpToolParam(description = "Em retratos, true compara com o cálculo de agora.", required = false) Boolean comparar_agora) {
        return Respostas.leitura(() -> resolverReferencia.resolver(Chamadores.de(contexto), ref, comparar_agora));
    }
}
