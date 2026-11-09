package tools.alicerce.financeiro.adaptador.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.AnexarComprovante;
import tools.alicerce.financeiro.aplicacao.ConferirSaldo;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.RegistrarTransferencia;
import tools.alicerce.financeiro.aplicacao.ResolverOcorrencia;
import tools.alicerce.seguranca.EscopoExigido;

@Component
class FerramentasFatos {

    private final RegistrarGasto registrarGasto;
    private final RegistrarGanho registrarGanho;
    private final CorrigirLancamento corrigirLancamento;
    private final AnexarComprovante anexarComprovante;
    private final RegistrarTransferencia registrarTransferencia;
    private final ConferirSaldo conferirSaldo;
    private final ResolverOcorrencia resolverOcorrencia;

    FerramentasFatos(RegistrarGasto registrarGasto, RegistrarGanho registrarGanho, CorrigirLancamento corrigirLancamento,
            AnexarComprovante anexarComprovante, RegistrarTransferencia registrarTransferencia, ConferirSaldo conferirSaldo,
            ResolverOcorrencia resolverOcorrencia) {
        this.conferirSaldo = conferirSaldo;
        this.resolverOcorrencia = resolverOcorrencia;
        this.registrarGasto = registrarGasto;
        this.registrarGanho = registrarGanho;
        this.corrigirLancamento = corrigirLancamento;
        this.anexarComprovante = anexarComprovante;
        this.registrarTransferencia = registrarTransferencia;
    }

    @McpTool(
            name = "registrar_gasto",
            title = "Registrar gasto",
            description = """
                    Registra um gasto dito na conversa ou lido de uma imagem. Se a leitura de uma imagem for incerta em \
                    algum campo, confirme com o dono antes de registrar; se a imagem tem data, envie-a. A categoria é \
                    obrigatória: pergunte ao dono se não souber. Gere uma chave_idempotencia por intenção do dono e \
                    reutilize-a se repetir a chamada. No crédito, use o meio do cartão, e parcelas de 1 a 48: valor é sempre o \
                    preço da compra inteira, nunca o da parcela. Com juros, envie também total_com_juros. Na saída, valor é o \
                    gasto inteiro, e as parcelas em credito já estão dentro dele: nunca some as duas coisas. Compra em outra moeda: envie \
                    valor_original e moeda_original, mais valor (o que saiu no extrato, de preferência) ou cotacao. O servidor \
                    calcula o que faltar; nunca faça a conta. Na saída, valor é o que vale, e original só descreve a compra. O meio \
                    precisa sair de conta na moeda do dono: conta em outra moeda é recusada e só recebe e envia transferências. Fato com \
                    data anterior ao marco da conta (obter_contexto) entra por inteiro e não muda o saldo: procedencia exogeno. Compra \
                    parcelada em andamento: envie a data real da compra, o número real de parcelas e o valor cheio, ou valor_parcela no \
                    lugar dele; nunca lance só o que falta. O servidor deduz a numeração; parcela_atual é só conferência. Parcelas \
                    vencidas antes do marco não geram fatura nem dívida. Se uma parcela cair em fatura com saldo de abertura, o servidor \
                    pede parcela_na_abertura: pergunte ao dono, nunca presuma. Use simular antes de gravar. Em credito.em_andamento, \
                    as partes já estão dentro de valor. Se o gasto paga um compromisso (aluguel, parcela de tributo, dívida com \
                    alguém), envie compromisso e, se souber, ocorrencia: o servidor baixa a parcela e devolve em ocorrencia quanto falta. \
                    Pagar só parte de uma parcela vale: ela continua em aberto pelo que falta (ocorrencia.falta_na_ocorrencia), \
                    atrasada depois do vencimento, e o próximo pagamento dela completa. Valor acima do que falta quita a parcela e \
                    mostra a diferença, sem reduzir a próxima. Compra parcelada no cartão nunca paga compromisso: as parcelas já estão nas faturas.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:write")
    public CallToolResult registrarGasto(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "Total da compra na moeda da conta, como \"45.90\". Obrigatório, salvo com valor_original e cotacao.", required = false) String valor,
            @McpToolParam(description = "Valor na moeda original, como \"20.00\". Só em compra em outra moeda.", required = false) String valor_original,
            @McpToolParam(description = "Moeda original, código ISO 4217, como USD.", required = false) String moeda_original,
            @McpToolParam(description = "Cotação usada, como \"5.52\". Sem valor, o servidor calcula o valor por ela.", required = false) String cotacao,
            @McpToolParam(description = "Data da cotação, AAAA-MM-DD; padrão: a data do lançamento.", required = false) String data_cotacao,
            @McpToolParam(description = "O que foi comprado. Obrigatória.", required = false) String descricao,
            @McpToolParam(description = "Meio de pagamento: id, nome ou alias. Obrigatório.", required = false) String meio_pagamento,
            @McpToolParam(description = "conversa, foto_nota ou print_app. Obrigatória.", required = false) String origem,
            @McpToolParam(description = "Data do gasto, AAAA-MM-DD; padrão: hoje.", required = false) String data,
            @McpToolParam(description = "Categoria: id, nome ou alias. Obrigatória sem itens; com itens, vale para os que não têm a sua.", required = false) String categoria,
            @McpToolParam(description = "Âmbito; ausente = padrão do meio, depois padrão do dono.", required = false) String ambito,
            @McpToolParam(description = "Linhas da nota. A soma dos valores tem de ser igual ao total.", required = false) List<ItemEntrada> itens,
            @McpToolParam(description = "CNPJ do estabelecimento.", required = false) String cnpj_estabelecimento,
            @McpToolParam(description = "Chave de acesso da NFC-e, 44 dígitos.", required = false) String chave_nfce,
            @McpToolParam(description = "Quem recebeu.", required = false) String contraparte,
            @McpToolParam(description = "Observação livre.", required = false) String observacao,
            @McpToolParam(description = "true depois que o dono confirmar que não é duplicata.", required = false) Boolean confirmar_duplicata,
            @McpToolParam(description = "true devolve o que seria gravado, sem gravar.", required = false) Boolean simular,
            @McpToolParam(description = "Em quantas vezes, de 1 a 48. Só no crédito; padrão: 1.", required = false) Integer parcelas,
            @McpToolParam(description = "Total pago com juros, como \"120.00\", só em compra parcelada com juros. Nesse caso, valor é o preço à vista.", required = false) String total_com_juros,
            @McpToolParam(description = "Valor de cada parcela, como \"214.53\", no lugar do total, com parcelas. Nunca os dois somados.", required = false) String valor_parcela,
            @McpToolParam(description = "Conferência: a parcela da próxima fatura a vencer, segundo o dono. Recusada se não bater com o cálculo.", required = false) Integer parcela_atual,
            @McpToolParam(description = "Só se o servidor pedir: true se a parcela já está dentro do saldo de abertura da fatura; false se é dívida a mais.", required = false) Boolean parcela_na_abertura,
            @McpToolParam(description = "O compromisso que este gasto paga: id k-, nome ou alias. Baixa uma ocorrência.", required = false) String compromisso,
            @McpToolParam(description = "Qual ocorrência: número da parcela, mês (AAAA-MM) ou data do vencimento. Ausente: a única em aberto perto da data.", required = false) String ocorrencia) {
        return Respostas.escrita(() -> registrarGasto.executar(Chamadores.de(contexto), new RegistrarGasto.Entrada(
                chave_idempotencia, lote, valor, descricao, meio_pagamento, origem, data, categoria, ambito,
                itens == null ? null : itens.stream().map(ItemEntrada::item).toList(),
                cnpj_estabelecimento, chave_nfce, contraparte, observacao, confirmar_duplicata, simular,
                new EntradaMoeda(valor_original, moeda_original, cotacao, data_cotacao), parcelas, total_com_juros,
                valor_parcela, parcela_atual, parcela_na_abertura, compromisso, ocorrencia)));
    }

    @McpTool(
            name = "registrar_transferencia",
            title = "Registrar transferência",
            description = """
                    Move dinheiro entre contas do próprio dono. Com um cartão em ate, é o pagamento de uma fatura: sem \
                    fatura, paga a fechada mais antiga com saldo; valor menor que o saldo deixa a fatura parcialmente paga, e o \
                    restante segue devido. Não é gasto: o gasto entrou na compra, e nem esta tool nem o pagamento contam como \
                    entrada ou saída nos relatórios. Data anterior ao marco de uma das contas é recusada. Entre contas de moedas \
                    diferentes é um câmbio: valor é sempre o lado na moeda do dono, e valor_original o lado na outra moeda, de qualquer \
                    lado que o dinheiro saia. Envie dois destes três: valor, valor_original e cotacao. O servidor calcula o terceiro e \
                    guarda a cotação; nunca faça a conta. Sem isso, a mistura de moedas é recusada. Na saída, valor e original são os dois \
                    lados do mesmo dinheiro: nunca os some. Câmbio é entre a moeda do dono e outra; fatura só se paga da moeda do \
                    cartão. Entre contas da mesma moeda estrangeira, valor está nessa moeda. Com meta, é um aporte na meta \
                    ("guardei R$ 300 para a viagem"): não é gasto e soma ao progresso dela; resgate true tira dinheiro da meta. Meta com \
                    conta reservada: o aporte entra nela e o resgate sai dela, e o sentido vem daí. A saída traz a meta com o progresso \
                    novo, quanto falta e o aporte mensal necessário: repasse como vieram. Gere uma chave_idempotencia por intenção do dono \
                    e reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:write")
    public CallToolResult registrarTransferencia(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "Quanto, como \"1234.56\". Num câmbio, o lado na moeda do dono.", required = false) String valor,
            @McpToolParam(description = "Só em câmbio: o lado na outra moeda, como \"1000.00\".", required = false) String valor_original,
            @McpToolParam(description = "Só em câmbio: quanto vale uma unidade da outra moeda na moeda do dono, como \"5.50\".", required = false) String cotacao,
            @McpToolParam(description = "Só em câmbio: data da cotação, AAAA-MM-DD; padrão: a data da transferência.", required = false) String data_cotacao,
            @McpToolParam(description = "Conta de onde o dinheiro sai. Obrigatória, salvo ao pagar cartão com conta_pagamento.", required = false) String de,
            @McpToolParam(description = "Conta para onde o dinheiro vai, ou o cartão, ao pagar fatura. Obrigatória.", required = false) String ate,
            @McpToolParam(description = "Data, AAAA-MM-DD; padrão: hoje.", required = false) String data,
            @McpToolParam(description = "A fatura paga: id f- ou período, como 2026-11. Só com cartão em ate.", required = false) String fatura,
            @McpToolParam(description = "Descrição; ausente = gerada.", required = false) String descricao,
            @McpToolParam(description = "Observação livre.", required = false) String observacao,
            @McpToolParam(description = "true depois que o dono confirmar que não é duplicata.", required = false) Boolean confirmar_duplicata,
            @McpToolParam(description = "true devolve o que seria gravado, sem gravar.", required = false) Boolean simular,
            @McpToolParam(description = "A meta que a transferência aporta ou resgata: id m-, nome ou alias.", required = false) String meta,
            @McpToolParam(description = "Com meta: true se o dinheiro sai da meta (resgate); padrão: aporte.", required = false) Boolean resgate) {
        return Respostas.escrita(() -> registrarTransferencia.executar(Chamadores.de(contexto), new RegistrarTransferencia.Entrada(
                chave_idempotencia, lote, valor, de, ate, data, fatura, descricao, observacao, confirmar_duplicata, simular,
                new EntradaMoeda(valor_original, null, cotacao, data_cotacao), meta, resgate)));
    }

    @McpTool(
            name = "registrar_ganho",
            title = "Registrar ganho",
            description = """
                    Registra uma entrada de dinheiro numa conta. A categoria é a fonte de renda e é obrigatória: pergunte \
                    ao dono se não souber. Gere uma chave_idempotencia por intenção do dono e reutilize-a se repetir a chamada. \
                    Recebimento em outra moeda: envie valor_original e moeda_original, mais valor (o que caiu na conta, de \
                    preferência) ou cotacao. O servidor calcula o que faltar; nunca faça a conta. Na saída, valor é o que vale, \
                    e original só descreve o recebimento. A conta precisa estar na moeda do dono: conta em outra moeda só recebe \
                    transferências. Data anterior ao marco da conta: entra por inteiro, não muda o saldo e sai como \
                    procedencia exogeno.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:write")
    public CallToolResult registrarGanho(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "Valor recebido na moeda da conta, como \"1234.56\". Obrigatório, salvo com valor_original e cotacao.", required = false) String valor,
            @McpToolParam(description = "Valor na moeda original, como \"20.00\". Só em compra em outra moeda.", required = false) String valor_original,
            @McpToolParam(description = "Moeda original, código ISO 4217, como USD.", required = false) String moeda_original,
            @McpToolParam(description = "Cotação usada, como \"5.52\". Sem valor, o servidor calcula o valor por ela.", required = false) String cotacao,
            @McpToolParam(description = "Data da cotação, AAAA-MM-DD; padrão: a data do lançamento.", required = false) String data_cotacao,
            @McpToolParam(description = "De que se trata. Obrigatória.", required = false) String descricao,
            @McpToolParam(description = "Conta onde o dinheiro entrou. Obrigatória.", required = false) String conta,
            @McpToolParam(description = "Fonte de renda: id, nome ou alias. Obrigatória.", required = false) String categoria,
            @McpToolParam(description = "conversa, foto_nota ou print_app; padrão: conversa.", required = false) String origem,
            @McpToolParam(description = "Data, AAAA-MM-DD; padrão: hoje.", required = false) String data,
            @McpToolParam(description = "Âmbito; ausente = padrão do dono.", required = false) String ambito,
            @McpToolParam(description = "CNPJ de quem pagou.", required = false) String cnpj_estabelecimento,
            @McpToolParam(description = "Chave de acesso da NFC-e, 44 dígitos.", required = false) String chave_nfce,
            @McpToolParam(description = "Quem pagou.", required = false) String contraparte,
            @McpToolParam(description = "Observação livre.", required = false) String observacao,
            @McpToolParam(description = "true depois que o dono confirmar que não é duplicata.", required = false) Boolean confirmar_duplicata,
            @McpToolParam(description = "true devolve o que seria gravado, sem gravar.", required = false) Boolean simular,
            @McpToolParam(description = "O compromisso de entrada que este ganho quita: id k-, nome ou alias.", required = false) String compromisso,
            @McpToolParam(description = "Qual ocorrência: número da parcela, mês (AAAA-MM) ou data do vencimento. Ausente: a única em aberto perto da data.", required = false) String ocorrencia) {
        return Respostas.escrita(() -> registrarGanho.executar(Chamadores.de(contexto), new RegistrarGanho.Entrada(
                chave_idempotencia, lote, valor, descricao, conta, categoria, origem, data, ambito, cnpj_estabelecimento,
                chave_nfce, contraparte, observacao, confirmar_duplicata, simular,
                new EntradaMoeda(valor_original, moeda_original, cotacao, data_cotacao), compromisso, ocorrencia)));
    }

    @McpTool(
            name = "corrigir_lancamento",
            title = "Corrigir lançamento",
            description = """
                    Corrige ou anula um lançamento, sempre por estorno: o original nunca muda. Com acao corrigir, grava \
                    o estorno e um lançamento novo com as alteracoes, e a nota fiscal passa para o novo. Com acao anular, \
                    só estorna. Num câmbio, valor, valor_original e cotacao seguem as regras da moeda original, e os dois lados são \
                    refeitos; a moeda não muda. Numa compra parcelada, alcança todas as parcelas. Fatura com pagamento pode mudar, e o \
                    pagamento fica intacto, desde que o pago continue cabendo no novo total; se sobrar pagamento, é recusado com os números e \
                    os pagamentos como candidatos. A data de uma abertura não muda. Confirme com o dono antes, e use simular para mostrar o efeito. Gere uma \
                    chave_idempotencia por intenção do dono e reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:write")
    public CallToolResult corrigirLancamento(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "Id curto ou URL do lançamento. Obrigatório.", required = false) String lancamento,
            @McpToolParam(description = "corrigir ou anular. Obrigatória.", required = false) String acao,
            @McpToolParam(description = "O que muda, ao corrigir; o que não vier fica como está.", required = false) AlteracoesEntrada alteracoes,
            @McpToolParam(description = "Por que corrigir ou anular. Obrigatório.", required = false) String motivo,
            @McpToolParam(description = "true devolve o que seria gravado, sem gravar.", required = false) Boolean simular) {
        return Respostas.escrita(() -> corrigirLancamento.executar(Chamadores.de(contexto), new CorrigirLancamento.Entrada(
                chave_idempotencia, lote, lancamento, acao, alteracoes == null ? null : alteracoes.alteracoes(), motivo, simular)));
    }

    @McpTool(
            name = "conferir_saldo",
            title = "Conferir saldo",
            description = """
                    Confere o saldo que o dono vê no banco contra o calculado, numa data (padrão: hoje). Numa conta, informe conta \
                    e saldo, na moeda da conta; num cartão, a fatura (id f- ou o mês do vencimento) e o total que o banco mostra. \
                    Diferença zero deixa a conta conferida até a data. Com diferença, ela fica pendente, e a saída traz candidatos: \
                    ocorrências de compromisso esperadas e não registradas desde a conferência anterior e, numa fatura, as compras dela, \
                    as de valor igual à diferença primeiro. Mostre a diferença ao dono e pergunte o que faltou; nunca registre nada \
                    sem o sim dele. Se ele não souber explicar, repita com resolucao ajustar, só numa conta: a diferença vira \
                    lançamento contra Ajuste não identificado, que aparece no resumo como linha própria. Fatura não tem ajuste. Uma \
                    conta fica com conferência vencida 30 dias depois da última (ou do marco). Desfazer a operação desfaz a conferência \
                    e estorna o ajuste. Gere uma chave_idempotencia por intenção do dono e reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:write")
    public CallToolResult conferirSaldo(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "A conta ou o cartão: id, nome ou alias. Obrigatória, salvo com fatura por id f-.", required = false) String conta,
            @McpToolParam(description = "Só em cartão: a fatura, por id f- ou mês do vencimento (AAAA-MM).", required = false) String fatura,
            @McpToolParam(description = "O saldo que o banco mostra, na moeda da conta, como \"2340.00\"; numa fatura, o total dela. Obrigatório.", required = false) String saldo,
            @McpToolParam(description = "Data do saldo, AAAA-MM-DD; padrão: hoje.", required = false) String data,
            @McpToolParam(description = "nenhuma (padrão: a diferença fica pendente) ou ajustar (só conta, depois do sim do dono).", required = false) String resolucao,
            @McpToolParam(description = "true devolve o que seria gravado, sem gravar.", required = false) Boolean simular) {
        return Respostas.escrita(() -> conferirSaldo.executar(Chamadores.de(contexto), new ConferirSaldo.Entrada(chave_idempotencia, lote,
                conta, fatura, saldo, data, resolucao, simular)));
    }

    @McpTool(
            name = "resolver_ocorrencia",
            title = "Resolver ocorrência de compromisso",
            description = """
                    Resolve uma ocorrência de compromisso sem registrar lançamento novo. pular: esta não vence nem é devida ("a \
                    academia não cobrou este mês"). adiar: passa a vencer em nova_data. vincular: um gasto ou ganho já registrado \
                    pagou esta ocorrência ("aquele Pix de ontem era o aluguel"). Para um pagamento novo, use registrar_gasto ou \
                    registrar_ganho com compromisso, e não esta tool. Ocorrência paga não se pula: para reabri-la, anule ou desfaça \
                    o pagamento. Ocorrência paga só em parte continua em aberto: aceita outro vínculo, e pular encerra o que falta. \
                    Desfazer a operação devolve a ocorrência ao que era; desfazer um de vários pagamentos reabre só a parte dele. \
                    Confirme com o dono antes. Gere uma \
                    chave_idempotencia por intenção do dono e reutilize-a se repetir a chamada.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    @EscopoExigido("fin:write")
    public CallToolResult resolverOcorrencia(McpTransportContext contexto,
            @McpToolParam(description = "Chave única desta intenção, até 64 caracteres. Obrigatória.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "O compromisso: id k-, nome ou alias. Obrigatório.", required = false) String compromisso,
            @McpToolParam(description = "Qual ocorrência: número da parcela, mês (AAAA-MM) ou data do vencimento. Ausente: a única em aberto perto de hoje.", required = false) String ocorrencia,
            @McpToolParam(description = "pular, adiar ou vincular. Obrigatória.", required = false) String acao,
            @McpToolParam(description = "Só com adiar: o novo vencimento, AAAA-MM-DD.", required = false) String nova_data,
            @McpToolParam(description = "Só com vincular: id curto ou URL do gasto ou ganho que pagou.", required = false) String lancamento,
            @McpToolParam(description = "Observação livre, como o motivo.", required = false) String observacao) {
        return Respostas.escrita(() -> resolverOcorrencia.executar(Chamadores.de(contexto), new ResolverOcorrencia.Entrada(chave_idempotencia,
                lote, compromisso, ocorrencia, acao, nova_data, lancamento, observacao)));
    }

    @McpTool(
            name = "anexar_comprovante",
            title = "Anexar comprovante",
            description = """
                    Guarda uma imagem (JPEG, PNG, WebP ou HEIC, até 10 MB) e a liga a um lançamento vigente, inclusive                     antigo. A natureza diz o que ela é: comprovante (nota, recibo, print do Pix; o padrão) ou foto (o que                     foi comprado, só lembrança, nunca prova). Se o cliente repassa o anexo da conversa, envie-o em arquivo,                     com uma chave_idempotencia e a natureza. Sem arquivo, devolve url_anexo: um link de uso único, válido                     por 24 horas, para entregar ao dono; a página pergunta a natureza. Desfazer desvincula e mantém o arquivo.""",
            annotations = @McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false),
            metaProvider = MetaArquivo.class)
    @EscopoExigido("fin:write")
    public CallToolResult anexarComprovante(McpTransportContext contexto,
            @McpToolParam(description = "Id curto ou URL do lançamento. Obrigatório.", required = false) String lancamento,
            @McpToolParam(description = "O anexo da conversa, quando o cliente o repassa.", required = false) ArquivoEntrada arquivo,
            @McpToolParam(description = "Chave única desta intenção. Obrigatória com arquivo.", required = false) String chave_idempotencia,
            @McpToolParam(description = "Igual em todas as chamadas de um mesmo pedido do dono.", required = false) String lote,
            @McpToolParam(description = "comprovante (padrão) ou foto do que foi comprado. Vale com arquivo; pelo link, a página pergunta.",
                    required = false) String natureza) {
        return Respostas.escrita(() -> anexarComprovante.executar(Chamadores.de(contexto), new AnexarComprovante.Entrada(
                chave_idempotencia, lote, lancamento, arquivo == null ? null : arquivo.recebido(), natureza)));
    }
}
