package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import tools.alicerce.convencoes.Dinheiro;

/**
 * As contas do resumo de período. Recebe só movimentos vigentes de gastos e ganhos: estornos e
 * lançamentos anulados ficam de fora, na mesma regra da listagem. Entradas e saídas somam o lado da
 * categoria ou fonte de renda, para cada lançamento contar uma vez.
 */
public final class CalculoResumo {

    private static final int ESCALA_PERCENTUAL = 2;
    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private CalculoResumo() {}

    public record Totais(Dinheiro entradas, Dinheiro saidas) {

        public Dinheiro saldo() {
            return entradas.subtrair(saidas);
        }
    }

    /** Como um movimento é agrupado numa quebra. {@code chave} separa grupos de nome igual. */
    public record Grupo(String chave, String id, String nome) {}

    /** @param participacaoEntradas percentual do total de entradas; {@code null} se o grupo não tem entradas */
    public record LinhaQuebra(String id, String nome, Dinheiro entradas, Dinheiro saidas, BigDecimal participacaoEntradas,
            BigDecimal participacaoSaidas) {}

    public record Destaque(UUID lancamento, String codigo, TipoLancamento tipo, LocalDate data, String descricao, Dinheiro valor,
            boolean exogeno) {}

    /**
     * Um lançamento do período com o que ele soma no recorte, como a prestação de contas o lista.
     *
     * @param valor a soma dos movimentos do lado da categoria ou fonte de renda que caíram no recorte, sem sinal: num gasto
     *     dividido entre âmbitos, só a parte do âmbito filtrado
     */
    public record LancamentoDoPeriodo(UUID lancamento, String codigo, TipoLancamento tipo, LocalDate data, String descricao,
            String contraparte, UUID meio, Dinheiro valor) {}

    /** @param percentual sobre o valor anterior; {@code null} se ele é zero */
    public record Variacao(Dinheiro diferenca, BigDecimal percentual) {}

    public static Totais totais(List<FatoPeriodo> fatos, Currency moeda) {
        Dinheiro zero = Dinheiro.zero(moeda);
        Dinheiro entradas = zero;
        Dinheiro saidas = zero;
        for (FatoPeriodo fato : fatos) {
            if (fato.contaComSaldo()) {
                continue;
            }
            if (fato.entrada()) {
                entradas = entradas.somar(fato.absoluto());
            } else {
                saidas = saidas.somar(fato.absoluto());
            }
        }
        return new Totais(entradas, saidas);
    }

    /** A parte de {@link #totais} que veio de fora: já está dentro deles. */
    public static Totais exogenos(List<FatoPeriodo> fatos, Currency moeda) {
        return totais(fatos.stream().filter(FatoPeriodo::exogeno).toList(), moeda);
    }

    /** As linhas da quebra, das maiores às menores. A quebra por conta olha o lado do dinheiro. */
    public static List<LinhaQuebra> quebrar(List<FatoPeriodo> fatos, Quebra quebra, Function<FatoPeriodo, Grupo> grupo,
            Currency moeda) {
        boolean ladoDoDinheiro = quebra == Quebra.CONTA;
        Totais totais = totais(fatos, moeda);
        Dinheiro zero = Dinheiro.zero(moeda);
        Map<String, Grupo> grupos = new LinkedHashMap<>();
        Map<String, Dinheiro> entradas = new LinkedHashMap<>();
        Map<String, Dinheiro> saidas = new LinkedHashMap<>();
        for (FatoPeriodo fato : fatos) {
            if (fato.contaComSaldo() != ladoDoDinheiro) {
                continue;
            }
            Grupo g = grupo.apply(fato);
            grupos.putIfAbsent(g.chave(), g);
            (fato.entrada() ? entradas : saidas).merge(g.chave(), fato.absoluto(), Dinheiro::somar);
        }
        List<LinhaQuebra> linhas = new ArrayList<>();
        grupos.forEach((chave, g) -> {
            Dinheiro entrou = entradas.getOrDefault(chave, zero);
            Dinheiro saiu = saidas.getOrDefault(chave, zero);
            linhas.add(new LinhaQuebra(g.id(), g.nome(), entrou, saiu, percentual(entrou, totais.entradas()),
                    percentual(saiu, totais.saidas())));
        });
        linhas.sort(Comparator.comparing((LinhaQuebra l) -> l.entradas().somar(l.saidas())).reversed()
                .thenComparing(LinhaQuebra::nome));
        return linhas;
    }

    /** Os {@code limite} maiores lançamentos do tipo, somando só o lado da categoria ou fonte de renda. */
    public static List<Destaque> maiores(List<FatoPeriodo> fatos, TipoLancamento tipo, int limite, Currency moeda) {
        Map<UUID, Destaque> porLancamento = new LinkedHashMap<>();
        for (FatoPeriodo f : fatos) {
            if (f.contaComSaldo() || f.tipo() != tipo) {
                continue;
            }
            Destaque atual = porLancamento.get(f.lancamento());
            Dinheiro soma = (atual == null ? Dinheiro.zero(moeda) : atual.valor()).somar(f.absoluto());
            porLancamento.put(f.lancamento(), new Destaque(f.lancamento(), f.codigo(), f.tipo(), f.data(), f.descricao(), soma, f.exogeno()));
        }
        return porLancamento.values().stream()
                .sorted(Comparator.comparing(Destaque::valor).reversed().thenComparing(Destaque::data, Comparator.reverseOrder())
                        .thenComparing(Destaque::codigo))
                .limit(limite).toList();
    }

    /** Todos os lançamentos do recorte, do mais antigo ao mais novo, cada um com a soma do lado da categoria ou fonte de renda. */
    public static List<LancamentoDoPeriodo> lancamentos(List<FatoPeriodo> fatos, Currency moeda) {
        Map<UUID, LancamentoDoPeriodo> porLancamento = new LinkedHashMap<>();
        for (FatoPeriodo f : fatos) {
            if (f.contaComSaldo()) {
                continue;
            }
            LancamentoDoPeriodo atual = porLancamento.get(f.lancamento());
            Dinheiro soma = (atual == null ? Dinheiro.zero(moeda) : atual.valor()).somar(f.absoluto());
            porLancamento.put(f.lancamento(), new LancamentoDoPeriodo(f.lancamento(), f.codigo(), f.tipo(), f.data(), f.descricao(),
                    f.contraparte(), f.meio(), soma));
        }
        return porLancamento.values().stream()
                .sorted(Comparator.comparing(LancamentoDoPeriodo::data).thenComparing(LancamentoDoPeriodo::codigo))
                .toList();
    }

    public static Variacao variacao(Dinheiro atual, Dinheiro anterior) {
        Dinheiro diferenca = atual.subtrair(anterior);
        BigDecimal percentual = anterior.ehZero() ? null : diferenca.valor().multiply(CEM)
                .divide(anterior.valor().abs(), ESCALA_PERCENTUAL, RoundingMode.HALF_EVEN);
        return new Variacao(diferenca, percentual);
    }

    private static BigDecimal percentual(Dinheiro parte, Dinheiro total) {
        if (parte.ehZero() || total.ehZero()) {
            return null;
        }
        return parte.valor().multiply(CEM).divide(total.valor(), ESCALA_PERCENTUAL, RoundingMode.HALF_EVEN);
    }
}
