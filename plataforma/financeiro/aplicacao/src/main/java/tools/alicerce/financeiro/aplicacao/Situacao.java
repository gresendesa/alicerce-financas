package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code situacao}: os sinais objetivos do dia, do mais grave ao menos grave. Só leitura.
 *
 * @param sinais vazio se não há nada a apontar; {@code mensagem} diz isso
 * @param contagem quantos sinais há de cada severidade
 * @param ressalvas o que não pôde ser avaliado, e por quê; ausente se tudo foi avaliado
 * @param horizonteMeses os meses da projeção que {@code reserva_abaixo_projetada} e {@code parcelas_acima_teto} olham, o corrente inclusive
 * @param naoAvaliados os sinais do perfil sem a declaração de que dependem; ausente se todas existem
 */
public record Situacao(String dataReferencia, String moeda, List<SinalVisto> sinais, Contagem contagem, String mensagem,
        List<String> ressalvas, String nota, Integer horizonteMeses, List<NaoAvaliado> naoAvaliados) {

    public record Contagem(int critico, int aviso, int info) {}

    /**
     * Um sinal do perfil que não foi avaliado por falta de declaração: o assistente não deduz a regra, pergunta ao dono.
     *
     * @param declaracaoQueFalta o tipo de {@code declarar} que o acenderia
     */
    public record NaoAvaliado(String codigo, String declaracaoQueFalta, String motivo) {}

    /**
     * Os números de um sinal do perfil. Cada código preenche só os seus.
     *
     * @param mes o primeiro mês em que a regra é quebrada
     * @param meses todos os meses do horizonte em que é quebrada
     * @param saldoFinalP80 em reserva: o saldo final pessimista do primeiro mês abaixo
     * @param reservaMinima o valor da reserva que vale hoje; em meses, o equivalente
     * @param parcelas em teto: as parcelas do primeiro mês acima
     * @param renda em teto: a renda desse mês
     * @param comprometimento em teto: parcelas sobre renda, em %
     * @param teto o teto declarado, em %
     * @param parcelasPorMes em parcelas subindo: os 3 meses fechados e o corrente
     * @param poupancaObservada em poupança: (renda − saídas) ÷ renda nos meses observados, em %
     * @param poupancaDeclarada a intenção declarada, em %
     * @param mesesObservados os meses fechados que a poupança e o ritmo da meta olham
     * @param aportesLiquidos em meta: aportes menos resgates dos meses observados
     * @param ritmoMensal em meta: os aportes líquidos divididos pelos meses observados
     * @param aporteMensalNecessario em meta: o mesmo de {@code definir_meta}
     * @param mesProjetado em meta: o mês em que chega no ritmo de agora; ausente se o ritmo não é positivo
     * @param protegida em meta: declarada em {@code meta_protegida}
     * @param operacaoDeclaracao a operação que gravou a declaração usada
     */
    public record DetalhePerfil(String mes, List<String> meses, String saldoFinalP80, String reservaMinima, Integer reservaMesesDeGasto,
            String gastoMensalTipico, String parcelas, String renda, String comprometimento, String teto, List<MesParcelas> parcelasPorMes,
            String poupancaObservada, String poupancaDeclarada, String rendaObservada, String saidasObservadas, List<String> mesesObservados,
            String valorAlvo, String dataAlvo, String progresso, String falta, String aportesLiquidos, String ritmoMensal,
            String aporteMensalNecessario, Boolean prazoVencido, String mesProjetado, Boolean protegida, String operacaoDeclaracao) {}

    /** @param corrente o mês de hoje, com o que já venceu ou vence nele */
    public record MesParcelas(String periodo, String parcelas, boolean corrente) {}

    /**
     * Um sinal, com os dados que o sustentam e o link do que ele aponta. Cada código preenche só os seus campos.
     *
     * @param codigo {@code compromisso_atrasado}, {@code fatura_vencendo}, {@code conferencia_pendente},
     *     {@code conferencia_antiga}, {@code categoria_subindo}, {@code reserva_abaixo_projetada}, {@code parcelas_acima_teto},
     *     {@code parcelas_subindo}, {@code poupanca_abaixo} ou {@code meta_atrasada}
     * @param severidade {@code critico}, {@code aviso} ou {@code info}, por regra fixa
     * @param alvo {@code compromisso}, {@code fatura}, {@code conta}, {@code categoria}, {@code meta}, {@code declaracao} (o id e a url
     *     são da operação que a gravou) ou {@code parcelas}
     * @param id o id curto do alvo
     * @param url o link da tela do alvo
     * @param valor o que falta: da ocorrência (a paga em parte, pelo restante) ou da fatura
     * @param diasAtraso só em compromisso atrasado: dias desde o vencimento
     * @param diasParaVencer só em fatura: dias até o vencimento; negativo se já venceu
     * @param valorPrevisto o previsto da ocorrência, só se ela já foi paga em parte
     * @param valorPago o que já foi pago da ocorrência, só se ela já foi paga em parte
     * @param diferencaPendente só em conferência pendente: informado menos calculado, na moeda da conta
     * @param valeAte só em conferência antiga: o último dia em que a conferência valia
     * @param meses só em categoria subindo: o gasto de cada mês fechado, como em {@code resumo_periodo}
     * @param altas só em categoria subindo: o percentual de cada mês sobre o anterior
     * @param perfil só nos sinais do perfil: os números que o sustentam
     */
    public record SinalVisto(String codigo, String severidade, String alvo, String id, String url, String nome, String direcao,
            String vencimento, String valor, String moeda, String parcela, Long diasAtraso, Long diasParaVencer, String valorPrevisto,
            String valorPago, String ultimaConferencia, String diferencaPendente, String valeAte, List<MesVisto> meses,
            List<String> altas, DetalhePerfil perfil) {}

    /** O gasto da categoria num mês: {@code saidas} é o mesmo da quebra por categoria de {@code resumo_periodo}. */
    public record MesVisto(String periodo, String saidas) {}
}
