package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * O que a página pública de prestação de contas mostra, e nada mais: o âmbito, o período, os totais dele, a
 * quebra por categoria e os lançamentos. Nunca saldos, contas, nomes de meio de pagamento, ids internos nem outros
 * âmbitos. Guardado no retrato de {@code prestar_contas}: o link abre sempre isto, mesmo que o livro-razão mude depois.
 *
 * @param gastos o total de gastos do âmbito no período, na moeda do dono
 * @param entradas o total de entradas do âmbito no período
 * @param validaAte o último dia, no fuso do dono, em que o link abre; a revogação pode encerrá-lo antes
 * @param comprovantes só com {@code incluir_comprovantes}: as imagens de natureza comprovante; ausente sem ele
 * @param notas só com {@code incluir_comprovantes}: as chaves das NFC-e, em grupos de quatro dígitos; ausente sem ele
 */
public record PrestacaoVista(String ambito, String de, String ate, String moeda, String gastos, String entradas,
        int quantidadeLancamentos, String validaAte, List<CategoriaPrestada> categorias, List<LancamentoPrestado> lancamentos,
        List<ComprovantePrestado> comprovantes, List<NotaPrestada> notas) {

    public record CategoriaPrestada(String nome, String gastos, String entradas) {}

    /**
     * @param tipo {@code Gasto} ou {@code Entrada}
     * @param meio só o tipo do meio, como {@code Pix}; nunca o nome dele
     * @param valor sem sinal: num gasto dividido entre âmbitos, só a parte deste
     */
    public record LancamentoPrestado(String data, String tipo, String descricao, String estabelecimento, String meio, String valor) {}

    /**
     * @param codigo o id curto, só para a página montar o endereço do arquivo; a tela não o mostra
     * @param url o arquivo pelo próprio link; preenchida só quando a página abre, porque o token não é guardado
     */
    public record ComprovantePrestado(String codigo, String titulo, String url) {

        ComprovantePrestado comUrl(String url) {
            return new ComprovantePrestado(codigo, titulo, url);
        }
    }

    public record NotaPrestada(String data, String descricao, String chave) {}

    PrestacaoVista comComprovantes(List<ComprovantePrestado> comprovantes) {
        return new PrestacaoVista(ambito, de, ate, moeda, gastos, entradas, quantidadeLancamentos, validaAte, categorias, lancamentos,
                comprovantes, notas);
    }
}
