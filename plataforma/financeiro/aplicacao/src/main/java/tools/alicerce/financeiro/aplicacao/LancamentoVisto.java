package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Um lançamento nas saídas de leitura, com nomes em vez de ids.
 *
 * @param anulado o lançamento foi estornado e o estorno vale
 * @param estorna o id curto do lançamento que este estorna
 * @param estornadoPor o id curto do estorno deste lançamento, só enquanto ele estiver anulado: um estorno
 *     desfeito não aparece aqui, para o lançamento restabelecido não parecer estornado
 * @param itens as linhas da nota; só em {@code resolver_referencia}
 * @param comprovantes as provas do pagamento ligadas ao lançamento; só em {@code resolver_referencia}
 * @param fotos as fotos do que foi comprado; só em {@code resolver_referencia}
 * @param original como a compra aconteceu em outra moeda, ou o lado estrangeiro de um câmbio; {@code valor} continua sendo o que vale
 * @param parcelas em quantas vezes a compra foi feita; ausente à vista. {@code valor} é a compra inteira
 * @param precoAVista o preço sem juros, só para consulta
 * @param procedencia {@code endogeno} nasceu no sistema; {@code exogeno} veio de fora
 * @param contaHistorica a conta a que um fato exógeno pertence, sem mexer no saldo dela
 */
public record LancamentoVisto(String id, String url, String tipo, String data, String descricao, String valor,
        String moeda, String meioPagamento, String contraparte, String observacao, String origem, boolean anulado,
        String estorna, String estornadoPor, String operacao, List<MovimentoVisto> movimentos, List<ItemVisto> itens,
        List<ComprovanteVisto> comprovantes, OriginalVisto original, Integer parcelas, String precoAVista, String procedencia,
        String contaHistorica, List<ComprovanteVisto> fotos) {

    /** Separa as imagens pela natureza: comprovantes de um lado, fotos do outro. */
    LancamentoVisto comComprovantes(List<ComprovanteVisto> lista) {
        List<ComprovanteVisto> provas = lista.stream().filter(c -> !c.foto()).toList();
        List<ComprovanteVisto> daCompra = lista.stream().filter(ComprovanteVisto::foto).toList();
        return new LancamentoVisto(id, url, tipo, data, descricao, valor, moeda, meioPagamento, contraparte, observacao, origem,
                anulado, estorna, estornadoPor, operacao, movimentos, itens, provas.isEmpty() ? null : provas, original, parcelas, precoAVista,
                procedencia, contaHistorica, daCompra.isEmpty() ? null : daCompra);
    }

    /** Recebe imagem nova: vigente, nem anulado nem estorno. */
    boolean anexavel() {
        return !anulado && !"estorno".equals(tipo);
    }

    /**
     * @param fatura o id curto da fatura, em movimento de cartão
     * @param parcela {@code 2/3}, em compra parcelada
     * @param faturaAbertura o id curto da fatura cuja abertura já contém esta parcela
     * @param moeda a moeda do movimento: num câmbio, os movimentos têm moedas diferentes e nunca se somam
     */
    public record MovimentoVisto(String conta, String valor, String ambito, String fatura, String parcela, String faturaAbertura,
            String moeda) {}

    public record ItemVisto(String descricao, String quantidade, String valor, String categoria, String ambito) {}
}
