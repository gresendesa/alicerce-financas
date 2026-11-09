package tools.alicerce.financeiro.aplicacao;

/**
 * Uma linha da agenda: uma ocorrência de compromisso em aberto ou o restante de uma fatura.
 *
 * @param tipo {@code compromisso} ou {@code fatura}
 * @param id o id curto do compromisso ({@code k-}) ou da fatura ({@code f-})
 * @param direcao {@code saida} ou {@code entrada}; fatura é sempre saída
 * @param valor o que falta: da ocorrência (a paga em parte, pelo restante) ou da fatura
 * @param situacao {@code atrasada} ou {@code prevista}
 * @param parcela {@code 3/12} em compromisso parcelado; o número da ocorrência nos demais; ausente em fatura
 * @param vencimentoOriginal só em ocorrência adiada
 * @param valorPrevisto o previsto da ocorrência, só se ela já foi paga em parte
 * @param valorPago o que já foi pago da ocorrência, só se ela já foi paga em parte
 */
public record ItemAgenda(String tipo, String id, String url, String nome, String direcao, String vencimento, String valor, String moeda,
        String situacao, String parcela, String referencia, String contraparte, String vencimentoOriginal, String valorPrevisto,
        String valorPago) {}
