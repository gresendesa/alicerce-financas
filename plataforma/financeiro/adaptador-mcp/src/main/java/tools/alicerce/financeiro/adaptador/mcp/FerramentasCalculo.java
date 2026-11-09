package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.CortesNecessarios;
import tools.alicerce.financeiro.aplicacao.ObterSituacao;
import tools.alicerce.financeiro.aplicacao.Projetar;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.alicerce.financeiro.aplicacao.RitmoGastos;
import tools.alicerce.seguranca.EscopoExigido;

@Component
class FerramentasCalculo {

    private final ResumirPeriodo resumirPeriodo;
    private final ObterSituacao obterSituacao;
    private final RitmoGastos ritmoGastos;
    private final CortesNecessarios cortesNecessarios;
    private final Projetar projetar;

    FerramentasCalculo(ResumirPeriodo resumirPeriodo, ObterSituacao obterSituacao, RitmoGastos ritmoGastos, CortesNecessarios cortesNecessarios,
            Projetar projetar) {
        this.projetar = projetar;
        this.ritmoGastos = ritmoGastos;
        this.cortesNecessarios = cortesNecessarios;
        this.resumirPeriodo = resumirPeriodo;
        this.obterSituacao = obterSituacao;
    }

    @McpTool(
            name = "situacao",
            title = "Situação",
            description = """
                    O diagnóstico do dia, feito para tarefas agendadas: uma chamada, sem argumentos obrigatórios, só sinais \
                    objetivos, sem opinião, e nada é gravado. Cada sinal traz codigo, severidade (critico, aviso ou info, por regra \
                    fixa), os dados que o sustentam e o link (url) do que ele aponta. compromisso_atrasado: ocorrência vencida com \
                    valor em aberto, inclusive a paga em parte, pelo que falta; crítico com mais de 30 dias de atraso. \
                    fatura_vencendo: fatura com restante que vence em até 5 dias; crítica se vence hoje ou amanhã, ou se já venceu. \
                    conferencia_pendente: a última conferência da conta deixou diferença não resolvida. conferencia_antiga: conta \
                    sem conferência há mais de 30 dias. categoria_subindo: categoria cujo gasto subiu mais de 10% em cada um dos 3 \
                    últimos meses fechados, com o gasto de cada mês (o mesmo de resumo_periodo por categoria, em competência). \
                    Sinais do perfil, com os números em perfil: reserva_abaixo_projetada (o saldo final p80 da linha de base de \
                    projetar fica abaixo da reserva mínima em algum mês do horizonte; crítico no mês corrente ou no seguinte), \
                    parcelas_acima_teto (as parcelas de um mês do horizonte passam do teto declarado sobre a renda), \
                    parcelas_subindo (as parcelas cresceram nos 3 meses fechados e no corrente; info), poupanca_abaixo (a poupança \
                    dos 3 meses fechados fica abaixo da declarada) e meta_atrasada (meta ativa com data alvo que não chega no ritmo \
                    dos aportes líquidos dos 3 meses fechados, ou cuja data passou; crítica se a meta é protegida). horizonte_meses \
                    vai de 1 a 24, padrão 3. Sem a declaração de que um sinal depende, ele sai em nao_avaliados com a declaração \
                    que falta: pergunte ao dono, nunca deduza a regra. Os valores são os mesmos de obter_agenda, \
                    listar_compromissos e projetar: repasse-os como vieram, nunca some nem recalcule. Resuma ao dono os sinais, do \
                    mais grave ao menos grave, com os links. Sem sinais, a lista vem vazia e mensagem diz isso. ressalvas diz o que \
                    não pôde ser avaliado: repasse ao dono.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult situacao(McpTransportContext contexto,
            @McpToolParam(description = "Meses da projeção que os sinais de reserva e de parcelas olham, de 1 a 24, o mês de hoje "
                    + "inclusive; padrão 3.", required = false) Integer horizonte_meses) {
        return Respostas.leitura(() -> obterSituacao.executar(Chamadores.de(contexto), horizonte_meses));
    }

    @McpTool(
            name = "resumo_periodo",
            title = "Resumo de período",
            description = """
                    Quanto entrou e saiu num período, com quebras, os maiores lançamentos e a comparação com o período \
                    anterior equivalente. Entradas, saídas e quebras estão na moeda do dono, e os totais já consideram correções \
                    e anulações: repasse os números como vieram, sem somar nada por conta própria. Em competencia, compra no \
                    crédito conta inteira na data da compra; em caixa, parcela a parcela, no vencimento da fatura. Transferência \
                    e pagamento de fatura nunca contam. Em competencia, o que veio de fora conta pelo valor cheio, e historico diz quanto: \
                    esse valor já está dentro de entradas e saidas, nunca some. Em caixa, só conta o que passa pelas contas. Com \
                    aviso_historico, o período é parcial: repasse o aviso ao dono. Com conta em outra moeda, saldos traz o saldo de cada conta no \
                    fim do período, na moeda dela, e o total convertido para a moeda do dono: use esses números, nunca some moedas, e cite ao \
                    dono a cotacao e a data_cotacao de cada conversão, que é a última registrada por ele, e não a cotação do dia. Com \
                    aviso_sem_cotacao, o saldo ficou fora do total: repasse o aviso. Ganho ou perda cambial não é calculado. Cada chamada guarda um \
                    retrato imutável: cite o id s- e a URL ao dono: url_vista_viva recalcula a cada abertura. Para abrir um retrato depois, \
                    ou ver se ainda vale, use resolver_referencia. ajustes traz as diferenças de conferência aceitas no período, fora de \
                    entradas e saidas: mostre-as como linha própria. conferencias_vencidas lista as contas sem conferência há mais de 30 \
                    dias: diga ao dono que os números delas merecem menos confiança e ofereça conferir_saldo.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult resumoPeriodo(McpTransportContext contexto,
            @McpToolParam(description = "Data inicial, AAAA-MM-DD. Use com ate, ou use periodo.", required = false) String de,
            @McpToolParam(description = "Data final, AAAA-MM-DD, inclusive.", required = false) String ate,
            @McpToolParam(description = "Mês, semana ISO ou ano: 2026-09, 2026-W38 ou 2026. No lugar de de e ate.", required = false) String periodo,
            @McpToolParam(description = "Quebras: categoria, meio_pagamento, contraparte, conta ou ambito; padrão: categoria.", required = false) List<String> agrupar_por,
            @McpToolParam(description = "Só este âmbito: id, nome ou alias.", required = false) String ambito,
            @McpToolParam(description = "Nível em que as categorias são somadas: 1 junta tudo nas principais; padrão: cada categoria.", required = false) Integer profundidade,
            @McpToolParam(description = "competencia (quando comprou) ou caixa (quando vence); padrão: competencia.", required = false) String visao,
            @McpToolParam(description = "false tira a comparação com o período anterior; padrão: true.", required = false) Boolean comparar_anterior) {
        return Respostas.leitura(() -> resumirPeriodo.executar(Chamadores.de(contexto), new ResumirPeriodo.Entrada(
                de, ate, periodo, agrupar_por, ambito, profundidade, visao, comparar_anterior)));
    }

    @McpTool(
            name = "ritmo_gastos",
            title = "Ritmo de gastos",
            description = """
                    Responde "estou gastando mais rápido que o normal, e o que está puxando isso?". Compara o gasto do mês até hoje \
                    (acumulado) com o típico: a mediana do que os meses de referência gastaram até o mesmo dia; num mês mais curto, até o \
                    último dia dele. Traz o ritmo diário, o atual e o típico, e duas projeções de fim de mês, cada uma com nome: \
                    mantido_ritmo_atual e acumulado_mais_restante_tipico; cite as duas pelo nome. Num mês já fechado, compara o mês inteiro \
                    e não há projeções. responsaveis traz as categorias, as contrapartes e as compras atípicas que puxam a diferença, das \
                    maiores para as menores; tendencia, a variação de cada categoria nos 3 últimos meses fechados, com a marca subindo de \
                    situacao. variabilidade responde "quais categorias variam mais?": o menor, a mediana e o maior mês de cada \
                    categoria nos meses de referência e a dispersão em %, da que mais varia para a que menos. Com intencao_poupanca \
                    declarada, poupanca traz a declarada contra a observada nos 3 meses fechados antes de hoje, o mesmo número do sinal \
                    poupanca_abaixo. Só vale mês de referência fechado inteiro depois do marco de todas as contas: com menos de 3, não há \
                    típico, responsáveis nem variabilidade, e a ressalva historico_insuficiente diz quantos havia e desde quando o \
                    histórico está completo. \
                    Repasse as ressalvas e nunca invente um típico nem recalcule os números: eles já vêm prontos. Sem incluir_fixos, ficam \
                    fora os pagamentos de compromisso e, em caixa, as parcelas de compras de meses anteriores; com ele, o total de cada mês \
                    de referência é o de resumo_periodo. Cada chamada guarda um retrato imutável: cite o id s- e a URL ao dono. Para ver \
                    depois se ainda vale, use resolver_referencia com comparar_agora.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult ritmoGastos(McpTransportContext contexto,
            @McpToolParam(description = "O mês, como 2026-11; padrão: o mês de hoje.", required = false) String periodo,
            @McpToolParam(description = "Quantos meses antes servem de referência, de 3 a 24; padrão: 6.", required = false) Integer meses_referencia,
            @McpToolParam(description = "true inclui pagamentos de compromisso e parcelas antigas; padrão: false.", required = false) Boolean incluir_fixos,
            @McpToolParam(description = "competencia (quando comprou) ou caixa (quando vence); padrão: competencia.", required = false) String visao) {
        return Respostas.leitura(() -> ritmoGastos.executar(Chamadores.de(contexto), new RitmoGastos.Entrada(
                periodo, meses_referencia, incluir_fixos, visao)));
    }

    @McpTool(
            name = "cortes_necessarios",
            title = "Cortes necessários",
            description = """
                    Responde "o que preciso deixar de gastar para fechar o mês no verde?", só no mês corrente. Projeta o saldo no fim do \
                    período: saldo de partida (contas corrente, carteira e poupança, na moeda do dono) mais as entradas conhecidas, menos \
                    as saídas conhecidas (ocorrências de compromisso em aberto e o restante das faturas que vencem até lá, atrasadas \
                    inclusive) e menos o gasto variável restante de cada categoria (o típico dos últimos 6 meses, sem compras atípicas, \
                    menos o já gasto). Devolve a distância até o alvo (folga ou deficit), os itens nao_cortaveis, as categorias com o \
                    gasto até hoje, o típico, o restante esperado e o espaço de corte, e um plano mínimo que cobre o déficit pelo espaço \
                    de cada categoria; as essenciais declaradas só entram depois das outras. Alvo: piso_periodo usa a restrição \
                    declarada com declarar; saldo_final é um saldo avulso numa data do mês; meta ("fechar no verde e ainda guardar para \
                    a viagem") exige no fim do mês o piso declarado (zero sem piso) mais o aporte mensal necessário da meta, e alvo.meta \
                    traz os dois. Conta reservada a uma meta fica fora do saldo de partida. estimativa p80 (padrão) é conservadora; p50 \
                    usa a mediana. O cálculo é conservador também no cartão: o gasto restante conta inteiro no mês. Todos os números \
                    vêm prontos: apresente-os como vieram, nunca some nem recalcule, e negocie o plano com o dono chamando de novo com \
                    outra estimativa ou outro alvo. Sem plano na resposta, não sugira cortes nem categorias por conta própria. \
                    Repasse sempre as ressalvas: com menos de 3 meses de histórico não há plano, e \
                    conferência vencida ou pendente põe o saldo de partida em dúvida. Cada chamada guarda um retrato imutável: cite o id \
                    s- e a URL ao dono; para ver depois se ainda vale, use resolver_referencia com comparar_agora. Para os meses \
                    seguintes ou para simular uma compra ("posso comprar isso?"), use projetar.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult cortesNecessarios(McpTransportContext contexto,
            @McpToolParam(description = "piso_periodo (a restrição declarada), saldo_final (um saldo avulso) ou meta (o piso mais o aporte do mês de uma meta); padrão: meta se vier meta, saldo_final se vier saldo_final, senão piso_periodo.", required = false) String alvo,
            @McpToolParam(description = "Com saldo_final: o saldo mínimo desejado, como \"500.00\".", required = false) String saldo_final,
            @McpToolParam(description = "Com saldo_final: a data, AAAA-MM-DD, de hoje até o fim do mês; padrão: o último dia do mês.", required = false) String data,
            @McpToolParam(description = "p80 (conservadora, padrão) ou p50 (mediana, otimista).", required = false) String estimativa,
            @McpToolParam(description = "Com alvo meta: a meta (id m-, nome ou alias), que precisa de data alvo.", required = false) String meta) {
        return Respostas.leitura(() -> cortesNecessarios.executar(Chamadores.de(contexto), new CortesNecessarios.Entrada(
                alvo, saldo_final, data, estimativa, meta)));
    }

    @McpTool(
            name = "projetar",
            title = "Projetar",
            description = """
                    Responde "posso comprar isso?" e "como fica meu saldo nos próximos meses?". Projeta o saldo mês a mês, em visão de \
                    caixa, de hoje até horizonte_meses (1 a 24, padrão 6; o mês de hoje conta, de hoje ao último dia). Cada mês soma ao \
                    saldo de partida (contas corrente, carteira e poupança, na moeda do dono) as entradas e saídas conhecidas (ocorrências \
                    de compromisso; as atrasadas contam no mês de hoje), as faturas que vencem nele (o já lançado no cartão, parcelas \
                    inclusive) e o gasto variável estimado de cada categoria, com a parte que costuma ir para o cartão no mês em que a \
                    fatura vence. Duas estimativas, sempre: p50, otimista (a mediana), e p80, pessimista de propósito (o percentil 80 de \
                    cada categoria, somado); não trate a p80 como previsão e diga qual é qual. Vem sempre a linha_base e, em cenarios, até \
                    3 cenários de hipóteses: compra (valor, meio_pagamento, parcelas, data; no crédito, cada parcela vai para a fatura em \
                    que registrar_gasto a poria), novo_compromisso (direcao, valor, tipo_compromisso, frequencia, primeiro_vencimento, fim), \
                    mudanca_renda (valor por mês, com sinal, de), mudanca_categoria (categoria, percentual, de) e aporte_meta (meta, valor \
                    por mês, de, ate; responde "se eu guardar R$ 500 por mês, chego lá?": os aportes saem do saldo na coluna aportes e \
                    param quando a meta chega no alvo). Cada cenário traz, em metas, a data projetada de cada meta ativa contra a data \
                    alvo, ou que não alcança no horizonte; a linha de base não tem aporte planejado. Conta reservada a uma meta fica fora \
                    do saldo de partida. Nada é gravado além do retrato. Para comparar formas de pagar, mande um cenário por forma numa só chamada. Indicadores de cada cenário: menor \
                    saldo e o mês nas duas estimativas, meses abaixo do piso_periodo e da reserva_minima declarados, o maior peso das \
                    faturas sobre as entradas, o maior peso das parcelas sobre a renda e a diferença para a linha de base. Cada mês traz \
                    renda, parcelas (de compras parceladas nas faturas e de compromissos parcelados; já estão dentro de faturas e saídas) \
                    e comprometimento (parcelas sobre renda, em %). violacoes lista, por cenário, as regras declaradas que ele quebra mês \
                    a mês pelo saldo p80: piso_periodo, reserva_minima, teto_parcelas e meta_protegida; regras traz as declaradas. Todos \
                    os números vêm prontos: apresente-os como vieram, nunca some nem recalcule. \
                    Repasse sempre as ressalvas: sem estimativa_variavel (menos de 3 meses de histórico), os saldos não descontam o gasto \
                    do dia a dia, e você não deve estimá-lo por conta própria; conferência vencida ou pendente põe o saldo de partida em \
                    dúvida. Cada chamada guarda um retrato imutável: cite o id s- e a URL ao dono; para ver depois se ainda vale, use \
                    resolver_referencia com comparar_agora.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:read")
    public CallToolResult projetar(McpTransportContext contexto,
            @McpToolParam(description = "Quantos meses projetar, de 1 a 24, contando o de hoje; padrão: 6.", required = false) Integer horizonte_meses,
            @McpToolParam(description = "Até 3 cenários, cada um com rotulo e hipoteses; sem eles, só a linha de base.", required = false) List<CenarioEntrada> cenarios) {
        return Respostas.leitura(() -> projetar.executar(Chamadores.de(contexto), new Projetar.Entrada(horizonte_meses,
                cenarios == null ? null : cenarios.stream().map(c -> c == null ? null : c.cenario()).toList())));
    }
}
