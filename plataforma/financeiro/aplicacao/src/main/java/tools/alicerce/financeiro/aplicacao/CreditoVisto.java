package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Como uma compra ficou no cartão. Fica separado do {@code valor} do lançamento, que é o gasto inteiro: as
 * parcelas aqui já estão dentro dele e nunca se somam a ele.
 *
 * @param parcelas em quantas vezes; 1 à vista
 * @param precoAVista o preço sem juros, só para consulta; ausente em compra sem juros
 * @param limiteDisponivel depois desta compra; ausente em cartão sem limite informado
 * @param faturas só as parcelas que viraram dívida neste cartão
 * @param emAndamento como se divide uma compra anterior ao marco; ausente nas demais
 */
public record CreditoVisto(int parcelas, String precoAVista, List<ParcelaVista> faturas, String limiteDisponivel,
        HistoricoCompra emAndamento) {

    /**
     * As três partes fecham com o valor da compra e já estão dentro dele: nunca se somam a ele.
     *
     * @param parcelaAtual a parcela da próxima fatura a vencer; ausente se a compra terminou
     * @param parcelasAnteriores quantas venceram antes do marco e não geraram fatura
     * @param valorAnterior quanto delas: já foi pago, fora do que o sistema cobre
     * @param valorNaAbertura a parte que já estava dentro do saldo de abertura, sem dívida nova
     * @param valorEmAberto a parte que virou dívida no cartão
     */
    public record HistoricoCompra(Integer parcelaAtual, int parcelasAnteriores, String valorAnterior, String valorNaAbertura,
            String valorEmAberto) {}

    /** @param parcela {@code 1/3}; ausente à vista */
    public record ParcelaVista(String fatura, String url, String periodo, String venceEm, String parcela, String valorDaParcela) {}
}
