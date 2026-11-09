package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code prestar_contas}, ao criar ou ao revogar um link. O resultado gravado na operação nunca tem a
 * URL pública: ela só existe na resposta da criação.
 *
 * @param retrato o id curto do retrato que o link mostra; também serve para revogar
 * @param urlPublica a página sem login; só na resposta da criação
 * @param validaAte o último dia em que o link abre, no fuso do dono
 * @param quantidadeComprovantes quantas imagens de comprovante a página mostra; zero sem {@code incluir_comprovantes}
 * @param quantidadeNotas quantas chaves de NFC-e a página mostra
 * @param oQueMostra o que repassar ao dono antes de ele enviar o link
 */
public record PrestacaoRegistrada(String retrato, String urlRetrato, String urlPublica, boolean revogado, String ambito, String de,
        String ate, String moeda, String gastos, String entradas, Integer quantidadeLancamentos, Integer quantidadeComprovantes,
        Integer quantidadeNotas, String validaAte, String oQueMostra, String proximoPasso) {

    PrestacaoRegistrada comUrl(String url) {
        return new PrestacaoRegistrada(retrato, urlRetrato, url, revogado, ambito, de, ate, moeda, gastos, entradas, quantidadeLancamentos,
                quantidadeComprovantes, quantidadeNotas, validaAte, oQueMostra, proximoPasso);
    }
}
