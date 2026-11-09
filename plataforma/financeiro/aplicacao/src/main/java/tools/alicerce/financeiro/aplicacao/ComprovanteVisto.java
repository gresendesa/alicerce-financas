package tools.alicerce.financeiro.aplicacao;

/**
 * Uma imagem ligada a um lançamento, nas saídas de leitura.
 *
 * @param natureza {@code comprovante} (prova do pagamento) ou {@code foto} (o que foi comprado)
 * @param lancamento o id curto do lançamento; {@code null} se foi desvinculado
 * @param urlTemporaria abre o arquivo pela aplicação, até {@code validaAte}
 */
public record ComprovanteVisto(String id, String natureza, String tipoMidia, int tamanhoBytes, String enviadoEm, String lancamento,
        String urlTemporaria, String validaAte) {

    boolean foto() {
        return "foto".equals(natureza);
    }
}
