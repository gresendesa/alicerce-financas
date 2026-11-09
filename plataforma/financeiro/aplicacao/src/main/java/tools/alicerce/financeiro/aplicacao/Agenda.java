package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code obter_agenda}. Os totais saem do domínio; parcela de cartão só aparece dentro da fatura.
 *
 * @param atrasados o que venceu e está em aberto, de qualquer data
 * @param vencimentos o que vence de hoje (ou de {@code de}, se depois) até {@code ate}
 * @param conferenciasVencidas as contas sem conferência há mais de 30 dias
 */
public record Agenda(String dataReferencia, String de, String ate, String moeda, List<ItemAgenda> atrasados, List<ItemAgenda> vencimentos,
        Totais totais, String nota, List<ConferenciaVista> conferenciasVencidas) {

    /** @param saldoPrevisto a receber menos a pagar, atrasado e no período juntos */
    public record Totais(String aPagarAtrasado, String aReceberAtrasado, String aPagarNoPeriodo, String aReceberNoPeriodo, String aPagar,
            String aReceber, String saldoPrevisto) {}
}
