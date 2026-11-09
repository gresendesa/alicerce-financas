package tools.alicerce.financeiro.aplicacao;

/** O corpo de uma escrita: roda dentro da transação do registrador e declara cada efeito. */
@FunctionalInterface
public interface Escrita<R> {

    R executar(OperacaoEmCurso operacao);
}
