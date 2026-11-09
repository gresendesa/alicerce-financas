package tools.alicerce.financeiro.aplicacao;

/**
 * A ocorrência que um pagamento baixou, como ficou depois dele.
 *
 * @param situacao a situação da ocorrência depois do pagamento: paga se quitou; prevista ou atrasada se ainda falta parte
 * @param valorPago a soma dos pagamentos da ocorrência, este incluído
 * @param diferenca pago menos previsto, só quando quitou; ausente se ainda falta parte
 * @param faltaNaOcorrencia quanto ainda falta desta ocorrência; ausente se quitou
 * @param valorRestante quanto falta no compromisso; ausente num recorrente sem fim
 */
public record OcorrenciaVinculada(String compromisso, String url, String nome, int numero, String parcela, String vencimento,
        String valorPrevisto, String situacao, String valorPago, String diferenca, String faltaNaOcorrencia, String valorRestante,
        Integer emAberto) {}
