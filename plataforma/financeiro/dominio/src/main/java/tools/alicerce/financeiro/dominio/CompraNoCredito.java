package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/**
 * Uma compra no crédito vigente, com tudo o que a visão caixa precisa.
 *
 * @param categorias os movimentos do lado da categoria, com o gasto inteiro
 * @param parcelas os movimentos do cartão e, em compra em andamento, os de Saldo de abertura, sem vencimento
 */
public record CompraNoCredito(UUID lancamento, String codigo, String descricao, String contraparte, UUID meio,
        List<Lado> categorias, List<Lado> parcelas) {

    /**
     * @param valor sem sinal
     * @param parcela o número da parcela; 1 em compra à vista. Zero no lado da categoria
     * @param vencimento o vencimento da fatura; nulo no lado da categoria e em parcela fora do cartão
     */
    public record Lado(UUID conta, UUID ambito, Dinheiro valor, int parcela, LocalDate vencimento) {}
}
