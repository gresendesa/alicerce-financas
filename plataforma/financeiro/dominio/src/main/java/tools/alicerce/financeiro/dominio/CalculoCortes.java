package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * As contas de {@code cortes_necessarios} (história 1) no mês corrente: o saldo projetado no último dia,
 * a distância até o alvo e o plano mínimo de cortes. Tudo em centavos da moeda do dono; quem converte em texto é
 * a aplicação. As regras:
 *
 * <ul>
 *   <li>saldo projetado = saldo de partida + entradas conhecidas − saídas conhecidas − gasto variável restante;
 *   <li>o restante de uma categoria é o típico do mês inteiro (percentil 80, ou a mediana na estimativa otimista)
 *       menos o já gasto, nunca negativo, e conta inteiro no mês, mesmo no cartão (decisões 4 e 5);
 *   <li>o espaço de corte é quanto o restante passa do percentil 25 do que a categoria gastou no mesmo trecho do
 *       mês nos meses de referência (decisão 6);
 *   <li>o plano cobre o déficit pelo espaço das categorias não essenciais, proporcional a ele; só o que sobra vai
 *       para as essenciais, do mesmo jeito (decisão 6);
 *   <li>com menos de {@value #MESES_MINIMOS} meses de histórico, sai a distância, mas não sai plano (decisão 7).
 * </ul>
 */
public final class CalculoCortes {

    /** Com menos meses de referência que isto, não há plano. */
    public static final int MESES_MINIMOS = CalculoRitmo.MESES_MINIMOS;
    /** O percentil do típico na estimativa conservadora. */
    public static final int PERCENTIL_CONSERVADOR = 80;
    /** O percentil abaixo do qual não se corta: o que a categoria gasta num trecho de mês enxuto. */
    public static final int PERCENTIL_PISO = 25;

    private CalculoCortes() {}

    /** Conservadora (percentil 80, o padrão) ou otimista (mediana). */
    public enum Estimativa {
        P80,
        P50;

        public String codigo() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static java.util.Optional<Estimativa> deCodigo(String codigo) {
            for (Estimativa e : values()) {
                if (e.codigo().equals(codigo)) {
                    return java.util.Optional.of(e);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /** O último dia do mês de {@code hoje}: o fim do período (decisão 1). */
    public static LocalDate fimDoPeriodo(LocalDate hoje) {
        return YearMonth.from(hoje).atEndOfMonth();
    }

    /**
     * O gasto variável de uma categoria, sem os que pagam compromisso e sem as compras atípicas. O fim do período é o
     * último dia do mês, ou a data de um alvo avulso antes dele.
     *
     * @param gastoAteHoje o que já foi gasto no mês, até hoje
     * @param totais em cada mês de referência, o gasto do dia 1 até o mesmo dia do fim do período; zero onde não gastou
     * @param restantes em cada mês de referência, o que gastou depois do mesmo dia de hoje até o mesmo dia do fim
     * @param essencial o dono a declarou essencial
     */
    public record Categoria(UUID id, long gastoAteHoje, List<Long> totais, List<Long> restantes, boolean essencial) {

        public Categoria {
            Objects.requireNonNull(id, "id");
            totais = List.copyOf(totais);
            restantes = List.copyOf(restantes);
            if (totais.size() != restantes.size()) {
                throw new IllegalArgumentException("Totais e restantes de meses diferentes");
            }
        }
    }

    /**
     * @param saldoPartida a soma das contas líquidas na moeda do dono, hoje
     * @param entradas as entradas conhecidas até o fim do mês (ocorrências de renda em aberto)
     * @param saidas as saídas conhecidas até o fim do mês, atrasadas inclusive (ocorrências e restante das faturas)
     * @param mesesReferencia quantos meses de referência valem; cada categoria traz um valor por mês
     * @param alvo o saldo mínimo no fim do mês
     */
    public record Entrada(long saldoPartida, long entradas, long saidas, List<Categoria> categorias, int mesesReferencia, long alvo,
            Estimativa estimativa) {

        public Entrada {
            categorias = List.copyOf(categorias);
            Objects.requireNonNull(estimativa, "estimativa");
            for (Categoria c : categorias) {
                if (c.totais().size() != mesesReferencia) {
                    throw new IllegalArgumentException("Categoria com " + c.totais().size() + " meses, esperados " + mesesReferencia);
                }
            }
        }
    }

    /**
     * Uma categoria no cálculo.
     *
     * @param tipico o típico do mês inteiro na estimativa pedida; zero sem histórico
     * @param restante o típico menos o já gasto, nunca negativo
     * @param piso o percentil 25 do que se gastou no mesmo trecho do mês; zero sem histórico
     * @param espaco quanto o restante passa do piso: o máximo que o plano corta dela
     * @param corte quanto o plano corta dela; zero sem plano
     */
    public record LinhaCategoria(UUID id, long gastoAteHoje, long tipico, long restante, long piso, long espaco, boolean essencial,
            long corte) {

        /** O restante depois do corte: o que o dono ainda pode gastar nela até o fim do período. */
        public long restanteComCorte() {
            return restante - corte;
        }
    }

    /**
     * @param saldoProjetado no último dia do mês
     * @param distancia o saldo projetado menos o alvo: positiva é folga, negativa é déficit
     * @param historicoSuficiente há {@value #MESES_MINIMOS} meses de referência ou mais: só então há plano
     * @param categorias da que tem mais espaço para a que tem menos
     * @param coberto quanto do déficit o plano cobre; zero sem déficit ou sem plano
     * @param descoberto o déficit que nem todo o espaço cobre; zero sem déficit ou sem plano
     * @param precisaEssenciais o plano só cobre o déficit cortando também categorias essenciais
     */
    public record Resultado(long saldoPartida, long entradas, long saidas, long variavelRestante, long saldoProjetado, long alvo,
            long distancia, boolean historicoSuficiente, List<LinhaCategoria> categorias, long espacoTotal, long coberto, long descoberto,
            boolean precisaEssenciais) {

        public Resultado {
            categorias = List.copyOf(categorias);
        }

        public long deficit() {
            return Math.max(0, -distancia);
        }

        /** As categorias com corte, as não essenciais antes, cada grupo do maior corte para o menor. */
        public List<LinhaCategoria> plano() {
            return categorias.stream().filter(c -> c.corte() > 0)
                    .sorted(Comparator.comparing(LinhaCategoria::essencial).thenComparing(LinhaCategoria::corte, Comparator.reverseOrder())
                            .thenComparing(c -> c.id().toString()))
                    .toList();
        }
    }

    /**
     * Monta as categorias a partir dos gastos, como em {@code ritmo_gastos}: os fixos (que pagam compromisso) ficam fora,
     * porque já estão nas saídas conhecidas, e as compras atípicas dos meses de referência também (decisão 4), pela mesma
     * regra de {@link CalculoRitmo#percentil}. Num mês mais curto, o mesmo dia é o último dele; o fim do mês é sempre o fim.
     *
     * @param doMes os gastos do mês corrente; os de depois de hoje ficam fora
     * @param referencia os gastos de cada mês de referência válido, inteiros; mês sem gasto entra com lista vazia
     * @param essenciais as categorias declaradas essenciais, com as subcategorias delas
     * @return por categoria, na ordem do id
     */
    public static List<Categoria> categorias(LocalDate hoje, LocalDate fim, List<CalculoRitmo.Gasto> doMes,
            java.util.Map<YearMonth, List<CalculoRitmo.Gasto>> referencia, java.util.Set<UUID> essenciais) {
        if (fim.isBefore(hoje) || !YearMonth.from(fim).equals(YearMonth.from(hoje))) {
            throw new IllegalArgumentException("O fim do período é de hoje até o fim do mês");
        }
        List<YearMonth> meses = new java.util.TreeSet<>(referencia.keySet()).stream().toList();
        java.util.Map<YearMonth, List<CalculoRitmo.Gasto>> ref = variaveis(referencia);
        java.util.Map<UUID, Long> limites = limitesAtipicos(ref);
        boolean fimDoMes = fim.equals(YearMonth.from(fim).atEndOfMonth());
        java.util.TreeMap<UUID, long[]> totais = new java.util.TreeMap<>();
        java.util.TreeMap<UUID, long[]> restantes = new java.util.TreeMap<>();
        for (int i = 0; i < meses.size(); i++) {
            YearMonth m = meses.get(i);
            LocalDate corteHoje = m.atDay(Math.min(hoje.getDayOfMonth(), m.lengthOfMonth()));
            LocalDate corteFim = fimDoMes ? m.atEndOfMonth() : m.atDay(Math.min(fim.getDayOfMonth(), m.lengthOfMonth()));
            for (CalculoRitmo.Gasto g : porCompra(ref.get(m)).values()) {
                Long limite = limites.get(g.categoria());
                if (limite != null && g.centavos() > limite || g.data().isAfter(corteFim)) {
                    continue;
                }
                totais.computeIfAbsent(g.categoria(), k -> new long[meses.size()])[i] += g.centavos();
                if (g.data().isAfter(corteHoje)) {
                    restantes.computeIfAbsent(g.categoria(), k -> new long[meses.size()])[i] += g.centavos();
                }
            }
        }
        java.util.TreeMap<UUID, Long> gastos = new java.util.TreeMap<>();
        doMes.stream().filter(g -> !g.fixo() && !g.data().isAfter(hoje)).forEach(g -> gastos.merge(g.categoria(), g.centavos(), Long::sum));
        java.util.TreeSet<UUID> ids = new java.util.TreeSet<>(totais.keySet());
        ids.addAll(gastos.keySet());
        List<Categoria> lista = new ArrayList<>();
        for (UUID id : ids) {
            long[] t = totais.getOrDefault(id, new long[meses.size()]);
            long[] r = restantes.getOrDefault(id, new long[meses.size()]);
            lista.add(new Categoria(id, gastos.getOrDefault(id, 0L), java.util.Arrays.stream(t).boxed().toList(),
                    java.util.Arrays.stream(r).boxed().toList(), essenciais.contains(id)));
        }
        return lista;
    }

    /** Os gastos de cada mês de referência sem os fixos (que pagam compromisso), na ordem dos meses. */
    static java.util.Map<YearMonth, List<CalculoRitmo.Gasto>> variaveis(java.util.Map<YearMonth, List<CalculoRitmo.Gasto>> referencia) {
        java.util.Map<YearMonth, List<CalculoRitmo.Gasto>> ref = new java.util.LinkedHashMap<>();
        new java.util.TreeSet<>(referencia.keySet()).forEach(m -> ref.put(m, referencia.get(m).stream().filter(g -> !g.fixo()).toList()));
        return ref;
    }

    /**
     * O limite acima do qual uma compra é atípica, por categoria: o percentil 95 das compras dos meses de referência,
     * pela regra de {@link CalculoRitmo#percentil}; categoria com poucas compras não tem limite.
     */
    static java.util.Map<UUID, Long> limitesAtipicos(java.util.Map<YearMonth, List<CalculoRitmo.Gasto>> variaveis) {
        java.util.Map<UUID, List<Long>> compras = new java.util.HashMap<>();
        variaveis.values().forEach(lista -> porCompra(lista).forEach((chave, g) ->
                compras.computeIfAbsent(g.categoria(), k -> new ArrayList<>()).add(g.centavos())));
        java.util.Map<UUID, Long> limites = new java.util.HashMap<>();
        compras.forEach((categoria, valores) -> {
            Long limite = CalculoRitmo.percentil(valores);
            if (limite != null) {
                limites.put(categoria, limite);
            }
        });
        return limites;
    }

    /** Junta as partes do mesmo lançamento na mesma categoria: uma compra. */
    static java.util.Map<String, CalculoRitmo.Gasto> porCompra(List<CalculoRitmo.Gasto> gastos) {
        java.util.Map<String, CalculoRitmo.Gasto> compras = new java.util.LinkedHashMap<>();
        for (CalculoRitmo.Gasto g : gastos) {
            compras.merge(g.lancamento() + "/" + g.categoria(), g, (a, b) -> new CalculoRitmo.Gasto(a.lancamento(), a.codigo(), a.data(),
                    a.categoria(), a.chaveContraparte(), a.contraparte(), a.descricao(), a.centavos() + b.centavos(), a.fixo()));
        }
        return compras;
    }

    public static Resultado calcular(Entrada e) {
        List<LinhaCategoria> linhas = new ArrayList<>();
        long variavel = 0;
        for (Categoria c : e.categorias()) {
            long tipico = c.totais().isEmpty() ? 0 : e.estimativa() == Estimativa.P80 ? percentil(c.totais(), PERCENTIL_CONSERVADOR)
                    : CalculoRitmo.mediana(c.totais());
            long restante = Math.max(0, tipico - c.gastoAteHoje());
            long piso = c.restantes().isEmpty() ? 0 : percentil(c.restantes(), PERCENTIL_PISO);
            long espaco = Math.max(0, restante - piso);
            variavel = Math.addExact(variavel, restante);
            linhas.add(new LinhaCategoria(c.id(), c.gastoAteHoje(), tipico, restante, piso, espaco, c.essencial(), 0));
        }
        long saldo = Math.subtractExact(Math.subtractExact(Math.addExact(e.saldoPartida(), e.entradas()), e.saidas()), variavel);
        long distancia = Math.subtractExact(saldo, e.alvo());
        long deficit = Math.max(0, -distancia);
        boolean suficiente = e.mesesReferencia() >= MESES_MINIMOS;
        long espacoTotal = linhas.stream().mapToLong(LinhaCategoria::espaco).sum();

        long coberto = 0;
        boolean essenciais = false;
        if (suficiente && deficit > 0) {
            long comuns = linhas.stream().filter(l -> !l.essencial()).mapToLong(LinhaCategoria::espaco).sum();
            long dasComuns = Math.min(deficit, comuns);
            long dasEssenciais = Math.min(deficit - dasComuns, espacoTotal - comuns);
            linhas = repartir(linhas, false, dasComuns);
            linhas = repartir(linhas, true, dasEssenciais);
            coberto = dasComuns + dasEssenciais;
            essenciais = dasEssenciais > 0;
        }
        linhas.sort(Comparator.comparing(LinhaCategoria::espaco, Comparator.reverseOrder())
                .thenComparing(LinhaCategoria::restante, Comparator.reverseOrder()).thenComparing(l -> l.id().toString()));
        return new Resultado(e.saldoPartida(), e.entradas(), e.saidas(), variavel, saldo, e.alvo(), distancia, suficiente, linhas,
                espacoTotal, coberto, suficiente ? deficit - coberto : 0, essenciais);
    }

    /**
     * Divide {@code total} entre as linhas do grupo, proporcional ao espaço de cada uma, sem passar dele. Cada parte
     * começa arredondada para baixo; os centavos que sobram vão, um a um, para as maiores sobras da divisão.
     */
    static List<LinhaCategoria> repartir(List<LinhaCategoria> linhas, boolean essencial, long total) {
        List<LinhaCategoria> saida = new ArrayList<>(linhas);
        if (total <= 0) {
            return saida;
        }
        List<Integer> grupo = new ArrayList<>();
        long soma = 0;
        for (int i = 0; i < saida.size(); i++) {
            LinhaCategoria l = saida.get(i);
            if (l.essencial() == essencial && l.espaco() > 0) {
                grupo.add(i);
                soma += l.espaco();
            }
        }
        if (total > soma) {
            throw new IllegalArgumentException("Corte maior que o espaço do grupo");
        }
        long[] partes = new long[grupo.size()];
        long[] sobras = new long[grupo.size()];
        long distribuido = 0;
        for (int k = 0; k < grupo.size(); k++) {
            long produto = Math.multiplyExact(saida.get(grupo.get(k)).espaco(), total);
            partes[k] = produto / soma;
            sobras[k] = produto % soma;
            distribuido += partes[k];
        }
        List<Integer> ordem = new ArrayList<>();
        for (int k = 0; k < grupo.size(); k++) {
            ordem.add(k);
        }
        ordem.sort(Comparator.comparing((Integer k) -> sobras[k], Comparator.reverseOrder())
                .thenComparing(k -> saida.get(grupo.get(k)).espaco(), Comparator.reverseOrder()).thenComparing(k -> k));
        for (int j = 0; distribuido < total; j++) {
            partes[ordem.get(j)]++;
            distribuido++;
        }
        for (int k = 0; k < grupo.size(); k++) {
            LinhaCategoria l = saida.get(grupo.get(k));
            saida.set(grupo.get(k), new LinhaCategoria(l.id(), l.gastoAteHoje(), l.tipico(), l.restante(), l.piso(), l.espaco(),
                    l.essencial(), partes[k]));
        }
        return saida;
    }

    /** O percentil pelo posto mais próximo: o menor valor que deixa pelo menos {@code p}% dos valores abaixo ou iguais a ele. */
    public static long percentil(List<Long> valores, int p) {
        if (valores.isEmpty()) {
            throw new IllegalArgumentException("Percentil sem valores.");
        }
        List<Long> ordenados = valores.stream().sorted().toList();
        int posto = Math.max(1, (p * ordenados.size() + 99) / 100);
        return ordenados.get(posto - 1);
    }
}
