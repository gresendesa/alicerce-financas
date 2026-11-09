package tools.alicerce.financeiro.aplicacao;

/**
 * Os parâmetros de um ritmo já resolvidos, como o retrato os guarda. {@code hoje} fica no retrato para a
 * comparação com o cálculo de agora usar o mesmo recorte, só com os dados novos.
 *
 * @param mes o mês analisado, {@code 2026-11}
 * @param hoje a data de hoje no fuso do dono quando o ritmo foi pedido
 * @param poupancaDeclarada a intenção de poupança vigente no pedido, em %; nula sem declaração
 * @param operacaoPoupanca a operação que a gravou
 */
public record ParametrosRitmo(String mes, String hoje, int mesesReferencia, boolean incluirFixos, String visao, String poupancaDeclarada,
        String operacaoPoupanca) {}
