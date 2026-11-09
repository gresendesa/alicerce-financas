package tools.alicerce.financeiro.aplicacao;

/**
 * O que toda escrita devolve: o resultado da capacidade e a operação gerada.
 *
 * @param operacao a operação gerada; {@code null} numa simulação, que não grava nada
 * @param repetido verdadeiro quando a mesma chamada já tinha sido feita: nada foi gravado de novo e
 *     o resultado é o original
 */
public record ResultadoEscrita<R>(R valor, OperacaoRegistrada operacao, boolean repetido) {

    public boolean simulado() {
        return operacao == null;
    }
}
