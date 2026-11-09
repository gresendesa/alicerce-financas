package tools.alicerce.financeiro.aplicacao;

import tools.alicerce.financeiro.dominio.ValorOriginal;

/**
 * Como a compra aconteceu em outra moeda, nas saídas. Fica num objeto próprio para não ser somada com
 * o {@code valor} do lançamento, que é sempre o que vale.
 */
public record OriginalVisto(String valorOriginal, String moedaOriginal, String cotacao, String dataCotacao) {

    /** @return {@code null} se a compra foi na moeda da conta */
    static OriginalVisto de(ValorOriginal original) {
        return original == null ? null : new OriginalVisto(original.valor().toPlainString(), original.moeda().getCurrencyCode(),
                original.cotacaoTexto(), original.dataCotacao().toString());
    }
}
