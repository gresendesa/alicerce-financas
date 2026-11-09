package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Um compromisso nas saídas. Todo número sai do domínio: quanto já foi, quanto falta e quando vence.
 *
 * @param valor o valor de cada ocorrência; em parcelado pelo total, o da parcela comum (a primeira leva a sobra)
 * @param situacao {@code ativo}, {@code quitado} (nada em aberto, num compromisso com fim) ou {@code encerrado}
 * @param proximas só em {@code definir_compromisso}: as próximas três em aberto
 * @param ocorrencias só na consulta de um compromisso: todas, num compromisso com fim; num recorrente, até um ano à frente
 */
public record CompromissoVisto(String id, String url, String nome, List<String> aliases, String direcao, String tipo, String referencia,
        String contraparte, String valor, String valorTotal, String moeda, String frequencia, String primeiroVencimento, Integer parcelas,
        Integer parcelasPagasAntes, String fim, String categoria, String conta, String meioPagamento, String ambito, String observacao,
        String situacao, SaldoVisto saldo, OcorrenciaVista proxima, List<OcorrenciaVista> proximas, List<OcorrenciaVista> ocorrencias) {

    /**
     * Quanto já foi e quanto falta.
     *
     * @param total quantas ocorrências o compromisso tem; ausente num recorrente sem fim
     * @param pagasEmParte quantas das em aberto já têm parte paga
     * @param valorPago a soma dos pagamentos vinculados, pelo valor real, inclusive os parciais
     * @param valorRestante o que falta do que está em aberto, já descontado o pago em parte; ausente num recorrente sem fim
     * @param valorAtrasado o que falta do que venceu e está em aberto
     */
    public record SaldoVisto(int pagas, int puladas, int emAberto, int atrasadas, Integer total, String valorPago, String valorRestante,
            String valorAtrasado, int pagasEmParte) {}

    CompromissoVisto comProximas(List<OcorrenciaVista> lista) {
        return new CompromissoVisto(id, url, nome, aliases, direcao, tipo, referencia, contraparte, valor, valorTotal, moeda, frequencia,
                primeiroVencimento, parcelas, parcelasPagasAntes, fim, categoria, conta, meioPagamento, ambito, observacao, situacao, saldo,
                proxima, lista, ocorrencias);
    }

    CompromissoVisto comOcorrencias(List<OcorrenciaVista> lista) {
        return new CompromissoVisto(id, url, nome, aliases, direcao, tipo, referencia, contraparte, valor, valorTotal, moeda, frequencia,
                primeiroVencimento, parcelas, parcelasPagasAntes, fim, categoria, conta, meioPagamento, ambito, observacao, situacao, saldo,
                proxima, proximas, lista);
    }
}
