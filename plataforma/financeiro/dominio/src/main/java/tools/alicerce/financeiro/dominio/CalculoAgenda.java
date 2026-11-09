package tools.alicerce.financeiro.dominio;

import java.util.List;

/** Os totais da agenda: o que entra e o que sai, atrasado e no período. Só soma, em centavos. */
public final class CalculoAgenda {

    /** Uma linha da agenda: uma ocorrência em aberto ou o restante de uma fatura. */
    public record Item(boolean saida, long centavos, boolean atrasado) {}

    /**
     * @param saldoPrevisto o que entra menos o que sai, atrasado e no período juntos
     */
    public record Totais(long aPagarAtrasado, long aReceberAtrasado, long aPagarNoPeriodo, long aReceberNoPeriodo, long aPagar,
            long aReceber, long saldoPrevisto) {}

    private CalculoAgenda() {}

    public static Totais totais(List<Item> itens) {
        long pagarAtrasado = 0;
        long receberAtrasado = 0;
        long pagar = 0;
        long receber = 0;
        for (Item i : itens) {
            if (i.saida()) {
                pagarAtrasado += i.atrasado() ? i.centavos() : 0;
                pagar += i.atrasado() ? 0 : i.centavos();
            } else {
                receberAtrasado += i.atrasado() ? i.centavos() : 0;
                receber += i.atrasado() ? 0 : i.centavos();
            }
        }
        long totalPagar = Math.addExact(pagarAtrasado, pagar);
        long totalReceber = Math.addExact(receberAtrasado, receber);
        return new Totais(pagarAtrasado, receberAtrasado, pagar, receber, totalPagar, totalReceber, Math.subtractExact(totalReceber, totalPagar));
    }
}
