package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Uma fatura nas saídas. {@code total} já é a soma de {@code itens}: cada item traz só o que cai nesta
 * fatura, e nunca o total da compra.
 *
 * @param id nulo enquanto a fatura do ciclo não tem nenhuma compra
 * @param situacao aberta, fechada, parcialmente_paga ou paga
 * @param restante o que falta pagar; num pagamento parcial, é o saldo devedor
 * @param itens só em {@code obter_fatura} e {@code resolver_referencia}
 * @param pagamentos só em {@code obter_fatura} e {@code resolver_referencia}
 * @param abertura o saldo de abertura desta fatura e quanto dele já se sabe o que é; ausente em fatura sem abertura
 */
public record FaturaVista(String id, String url, String cartao, String periodo, String fechaEm, String venceEm, String situacao,
        String total, String pago, String restante, String moeda, List<ItemVisto> itens, List<PagamentoVisto> pagamentos,
        AberturaVista abertura) {

    /** @param parcela {@code 2/3}; ausente em compra à vista */
    public record ItemVisto(String lancamento, String url, String data, String descricao, String tipo, String valorNaFatura,
            String parcela) {}

    public record PagamentoVisto(String lancamento, String url, String data, String valor) {}

    /**
     * Já está dentro de {@code total}: é só o detalhe do item de abertura, e nunca se soma a ele.
     *
     * @param valor o saldo de abertura nesta fatura
     * @param detalhado a parte dele que já tem compra ligada
     * @param semDetalhe a parte de que ainda não se sabe a compra
     * @param parcelas só em {@code obter_fatura} e {@code resolver_referencia}
     */
    public record AberturaVista(String valor, String detalhado, String semDetalhe, List<ParcelaDetalhada> parcelas) {}

    /** @param parcela {@code 8/12}; ausente em compra à vista */
    public record ParcelaDetalhada(String lancamento, String url, String descricao, String parcela, String valorNaAbertura) {}

    FaturaVista semDetalhes() {
        AberturaVista resumo = abertura == null ? null : new AberturaVista(abertura.valor(), abertura.detalhado(), abertura.semDetalhe(), null);
        return new FaturaVista(id, url, cartao, periodo, fechaEm, venceEm, situacao, total, pago, restante, moeda, null, null, resumo);
    }
}
