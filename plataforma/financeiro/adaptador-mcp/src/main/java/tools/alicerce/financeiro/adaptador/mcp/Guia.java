package tools.alicerce.financeiro.adaptador.mcp;

/**
 * O guia do Alicerce para qualquer agente, devolvido pela tool {@code guia}. Toda tool vem entre crases, e
 * só tools vêm entre crases: o teste confere que cada uma existe, que toda tool do servidor está aqui e que todo
 * prefixo de id curto aparece. Mudou uma tool, muda o guia junto. As {@code instructions} do {@code initialize}, que
 * apontam para cá, ficam em {@code spring.ai.mcp.server.instructions}.
 */
final class Guia {

    static final String TEXTO = """
            # Guia do Alicerce

            O Alicerce guarda com exatidão os fatos financeiros do dono, para que qualquer assistente raciocine com dados \
            reais, e não com suposições. Você pensa; o servidor registra, calcula e prova. Este guia vale para todas as \
            conversas: leia-o uma vez por conversa, antes da primeira resposta sobre o Alicerce.

            ## Comece sempre assim

            1. Chame `obter_contexto`: fuso e moeda do dono, contas, categorias, cartões, meios de pagamento, âmbitos, \
            declarações, metas e compromissos. conta_conectada diz qual login respondeu; com conta_de_teste, os dados são \
            fictícios: avise o dono e nunca os trate como a vida real dele.
            2. Para qualquer id curto ou URL do Alicerce colado na conversa, chame `resolver_referencia`. Todo id com \
            prefixo do Alicerce se resolve no servidor, nunca pela memória da conversa; não pergunte ao dono o que ele é: \
            o servidor sabe.

            ## Ids curtos

            Toda entidade tem um id curto: prefixo, hífen e seis caracteres, como t-8K3QZ2. A forma completa leva o \
            módulo (fin:t-8K3QZ2), e a URL da interface web é https://app.alicerce.tools/fin/t/8K3QZ2. Os prefixos:

            - t-: lançamento (um gasto, ganho, transferência, estorno ou ajuste).
            - a-: conta (corrente, poupança, carteira, cartão, categoria de gasto, fonte de renda).
            - f-: fatura de cartão de crédito.
            - k-: compromisso (aluguel, salário, assinatura, parcela de dívida).
            - m-: meta de valor (juntar R$ 6.000 para a viagem até dezembro de 2027).
            - n-: comprovante ou foto anexada a um lançamento.
            - s-: retrato, o resultado imutável de um cálculo guardado no momento em que foi feito.
            - o-: operação, cada escrita feita no Alicerce, com quem a fez e quando.

            ## Conceitos

            - Livro-razão imutável: lançamentos nunca são apagados nem editados. Corrigir é estornar e lançar de novo \
            (`corrigir_lancamento`); o histórico fica. Toda escrita vira uma operação (o-), listada em `listar_operacoes`.
            - Contas e partidas dobradas: cada lançamento tem movimentos que somam zero. Categorias de gasto e fontes de \
            renda também são contas. Saldo é sempre na moeda da própria conta: nunca some saldos de moedas diferentes.
            - Marco: em cada conta, a data desde quando o Alicerce responde pelo saldo. Fato anterior ao marco entra por \
            inteiro, como histórico (procedencia exogeno), e não muda o saldo. Período antes do marco pode estar \
            incompleto: repasse o aviso_historico.
            - Visões: competencia conta a compra no crédito inteira na data da compra; caixa conta parcela a parcela, no \
            vencimento da fatura. Transferência e pagamento de fatura nunca são gasto.
            - Cartões e faturas: compra parcelada distribui as parcelas nas faturas; `obter_fatura` mostra uma fatura, e o \
            pagamento é uma transferência (`registrar_transferencia`).
            - Âmbitos: para quem foi o gasto (pessoal, família, igreja...), independente da categoria. Um mesmo gasto pode \
            dividir itens entre âmbitos. Crie e altere com `definir_ambito`.
            - Compromissos e fixos: o que se repete, com ocorrências (parcelas) que vencem. `definir_compromisso` cria, \
            `listar_compromissos` mostra, `obter_agenda` lista o que vence, e `resolver_ocorrencia` paga, pula ou adia.
            - Conferência: o dono confirma o saldo real de uma conta com `conferir_saldo`; conta sem conferência há mais de \
            30 dias merece menos confiança.
            - Retratos (s-): `resumo_periodo`, `ritmo_gastos`, `cortes_necessarios`, `projetar` e `prestar_contas` guardam o resultado \
            para sempre. Para saber se um retrato ainda vale, use `resolver_referencia` com comparar_agora.
            - Desfazer: `desfazer` reverte uma operação ou um lote. A primeira chamada só mostra a prévia; mostre-a ao dono \
            e aplique só depois do sim dele.
            - Perfil: `declarar` grava as regras e intenções do dono, uma vigente por tipo: o saldo mínimo do fim do mês, as \
            categorias essenciais, a reserva mínima (um valor ou meses de gasto), quanto poupar da renda, o teto das parcelas \
            sobre a renda, as metas protegidas e uma nota livre. A reserva em meses vale os meses vezes o gasto mensal típico, \
            calculado pelo servidor. `situacao` vigia as regras declaradas (reserva abaixo na projeção, parcelas acima do teto \
            ou subindo, poupança abaixo da declarada, meta atrasada); sem a declaração, o sinal sai como não avaliado, e você \
            pergunta ao dono, sem deduzir a regra. A nota sai em `obter_contexto`: leia-a antes de responder.
            - Metas de valor: `definir_meta` cria ou altera uma meta (nome, valor alvo, data alvo, prioridade, conta \
            reservada). Aportar ou resgatar é uma transferência com meta (`registrar_transferencia`): o progresso é a soma \
            dos aportes menos os resgates registrados, nunca uma estimativa. A meta traz quanto falta e o aporte mensal \
            necessário até a data alvo. Conta reservada a uma meta fica fora do saldo disponível de `projetar` e de \
            `cortes_necessarios`.
            - Comprovantes: `anexar_comprovante` gera um link de envio ou recebe o arquivo; comprovante prova o pagamento, \
            foto é só lembrança.
            - Prestação de contas: `prestar_contas` publica um âmbito e um período num link que abre sem login. Exige o \
            escopo fin:share; um conector antigo recebe insufficient_scope até o dono reconectar.

            ## Para cada pergunta, a tool

            - Registrar: `registrar_gasto`, `registrar_ganho`, `registrar_transferencia`; estrutura com `definir_conta` \
            e `definir_meio_pagamento`.
            - Consultar: `listar_lancamentos`, `resumo_periodo` (totais, quebras e comparação), `ritmo_gastos` (estou \
            gastando mais rápido que o normal? quais categorias variam mais? estou poupando o que quero?), \
            `cortes_necessarios` (o que cortar para fechar o mês no verde), \
            `situacao` (sinais objetivos para checagens agendadas).
            - Planejar: `projetar` (posso comprar isso? como fica meu saldo nos próximos meses?) projeta o saldo mês a mês, \
            com a linha de base e até 3 cenários (compra, novo compromisso, mudança de renda ou de categoria). Traz duas \
            estimativas: p50 é a otimista e p80 a pessimista de propósito, que não é previsão. Para comparar formas de pagar, \
            mande um cenário por forma na mesma chamada. "Se eu guardar R$ 500 por mês, chego lá?" é um cenário com a \
            hipótese aporte_meta: o aporte sai do saldo de cada mês, e cada meta ganha a data projetada contra a data alvo. \
            "O que cortar para fechar no verde e ainda guardar para a meta?" é `cortes_necessarios` com alvo meta. Cada \
            cenário traz as parcelas e o peso delas na renda de cada mês, e as regras declaradas que ele quebra.
            - Mostrar uma tela: `mostrar_vista`, com o endereço de uma tela padrão ou uma vista composta só de \
            componentes do catálogo.
            - Este guia: `guia`.

            ## Boas práticas

            - Números só do servidor. Repasse valores como vieram; nunca some, subtraia, converta nem projete por conta \
            própria.
            - Quando falta um pedaço da resposta, como o plano de cortes, o típico do ritmo, a estimativa de gasto variável \
            da projeção ou uma cotação, não invente: \
            diga ao dono o que faltou e repasse as ressalvas.
            - Em erro com campos_faltando, pergunte ao dono exatamente o que falta; nunca chute um valor.
            - Gere uma chave_idempotencia por intenção do dono e reutilize-a ao repetir a mesma chamada; use lote para \
            agrupar as escritas de um mesmo pedido, e simular antes de gravar quando houver dúvida.
            - Cite o id e o link do que foi gravado ou calculado: o lançamento (t-), a operação (o-) e o retrato (s-).
            - Leitura de imagem incerta: confirme com o dono antes de registrar.
            - Antes de mandar um link de prestação de contas, diga ao dono que ele abre sem login e o que ele mostra.
            - Conta de teste (conta_de_teste em `obter_contexto`): avise o dono, sempre.
            """;

    private Guia() {}
}
