package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code anexar_comprovante} e da página de envio.
 *
 * @param lancamento o id curto do lançamento
 * @param comprovante o id curto da imagem; {@code null} quando só um link foi gerado
 * @param natureza {@code comprovante} ou {@code foto}; {@code null} quando só um link foi gerado, porque a
 *     página de envio é que pergunta
 * @param urlAnexo o link de envio. Nunca é gravado no resultado da operação
 * @param validaAte até quando o link vale
 */
public record AnexoRegistrado(String lancamento, String comprovante, String natureza, String urlAnexo, String validaAte) {

    AnexoRegistrado comLink(String url, String validade) {
        return new AnexoRegistrado(lancamento, comprovante, natureza, url, validade);
    }
}
