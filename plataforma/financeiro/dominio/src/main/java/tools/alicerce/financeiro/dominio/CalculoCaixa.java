package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.financeiro.dominio.CompraNoCredito.Lado;

/**
 * A visão caixa de uma compra no crédito: cada parcela conta na data de vencimento da fatura, paga ou
 * não. A divisão por categoria é a mesma em qualquer período, então os meses somam a compra exata. Parcela
 * de compra em andamento sem vencimento já estava paga ou dentro da abertura: entra na divisão e nunca conta.
 */
public final class CalculoCaixa {

    private CalculoCaixa() {}

    /**
     * Os fatos desta compra que vencem no período.
     *
     * @param ambito só os movimentos deste âmbito; nulo não filtra
     */
    public static List<FatoPeriodo> fatos(CompraNoCredito compra, Periodo periodo, UUID ambito) {
        Map<Integer, Long> porParcela = new TreeMap<>();
        Map<Integer, LocalDate> vencimentos = new TreeMap<>();
        for (Lado lado : compra.parcelas()) {
            porParcela.merge(lado.parcela(), lado.valor().centavos(), Long::sum);
            vencimentos.put(lado.parcela(), lado.vencimento());
        }
        List<Integer> numeros = List.copyOf(porParcela.keySet());
        long[] linhas = numeros.stream().mapToLong(porParcela::get).toArray();
        long[] colunas = compra.categorias().stream().mapToLong(c -> c.valor().centavos()).toArray();
        long[][] partes = Repartir.matriz(linhas, colunas);

        List<FatoPeriodo> fatos = new ArrayList<>();
        for (int p = 0; p < numeros.size(); p++) {
            LocalDate vencimento = vencimentos.get(numeros.get(p));
            if (vencimento == null || vencimento.isBefore(periodo.de()) || vencimento.isAfter(periodo.ate())) {
                continue;
            }
            for (int c = 0; c < colunas.length; c++) {
                Lado categoria = compra.categorias().get(c);
                if (partes[p][c] > 0 && (ambito == null || ambito.equals(categoria.ambito()))) {
                    fatos.add(fato(compra, vencimento, categoria.conta(), false, categoria.ambito(),
                            Dinheiro.deCentavos(partes[p][c], categoria.valor().moeda())));
                }
            }
            int numero = numeros.get(p);
            compra.parcelas().stream().filter(l -> l.parcela() == numero && (ambito == null || ambito.equals(l.ambito())))
                    .forEach(l -> fatos.add(fato(compra, vencimento, l.conta(), true, l.ambito(), l.valor())));
        }
        return fatos;
    }

    private static FatoPeriodo fato(CompraNoCredito c, LocalDate data, UUID conta, boolean contaComSaldo, UUID ambito, Dinheiro valor) {
        return new FatoPeriodo(c.lancamento(), c.codigo(), TipoLancamento.GASTO, data, c.descricao(), c.contraparte(), c.meio(),
                conta, contaComSaldo, ambito, valor);
    }
}
