package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import tools.alicerce.convencoes.ErroTipado;

/**
 * As contas de {@code ritmo_gastos}: o gasto do mês até hoje contra o típico no mesmo dia, as duas
 * projeções de fim de mês e quem puxa a diferença. Tudo em centavos da moeda do dono; quem converte em texto
 * é a aplicação.
 */
public final class CalculoRitmo {

    /** Meses de referência sem pedido. */
    public static final int MESES_PADRAO = 6;
    /** Com menos meses válidos que isto, não há típico (decisão 4). */
    public static final int MESES_MINIMOS = 3;
    /** O máximo de meses de referência que se pode pedir. */
    public static final int MESES_MAXIMOS = 24;
    /** O percentil acima do qual uma compra é atípica na categoria (decisão 6). */
    public static final int PERCENTIL = 95;
    /** Só categorias com tantas compras nos meses de referência têm percentil (decisão 6). */
    public static final int COMPRAS_MINIMAS = 20;
    /** Quantos responsáveis cada grupo mostra. */
    public static final int RESPONSAVEIS = 5;

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final int ESCALA_PERCENTUAL = 2;

    private CalculoRitmo() {}

    /**
     * Um gasto do lado da categoria, como na quebra por categoria de {@code resumo_periodo}.
     *
     * @param chaveContraparte o nome normalizado, que junta grafias da mesma contraparte; {@code null} sem contraparte
     * @param fixo paga uma ocorrência de compromisso ou, na visão caixa, é parcela de compra de mês anterior (decisão 5)
     */
    public record Gasto(UUID lancamento, String codigo, LocalDate data, UUID categoria, String chaveContraparte, String contraparte,
            String descricao, long centavos, boolean fixo) {

        public Gasto {
            Objects.requireNonNull(lancamento, "lancamento");
            Objects.requireNonNull(data, "data");
            Objects.requireNonNull(categoria, "categoria");
        }
    }

    /**
     * O mês analisado e o dia até onde se compara.
     *
     * @param dia o dia de hoje no mês em curso; o último dia num mês já fechado
     * @param emCurso o mês ainda não acabou: só então há projeções (decisão 10)
     */
    public record Recorte(YearMonth mes, int dia, boolean emCurso) {

        public static Recorte de(YearMonth mes, LocalDate hoje) {
            YearMonth atual = YearMonth.from(hoje);
            if (mes.isAfter(atual)) {
                throw ErroTipado.validacao("periodo", "O mês " + mes + " ainda não começou.");
            }
            return mes.equals(atual) ? new Recorte(mes, hoje.getDayOfMonth(), true) : new Recorte(mes, mes.lengthOfMonth(), false);
        }

        public int diasDoMes() {
            return mes.lengthOfMonth();
        }

        public LocalDate corte() {
            return mes.atDay(dia);
        }

        /** O mesmo dia em outro mês; num mês mais curto, o último dia dele (decisão 2). */
        public LocalDate corteEm(YearMonth outro) {
            return outro.atDay(Math.min(dia, outro.lengthOfMonth()));
        }
    }

    /**
     * Os meses de referência que valem: os {@code pedidos} meses antes do analisado, só os que começam depois
     * do marco mais recente das contas ativas com saldo, como em {@code categoria_subindo} (decisão 4).
     *
     * @param marcoMaisRecente {@code null} se nenhuma conta tem marco
     * @return do mais antigo ao mais recente
     */
    public static List<YearMonth> mesesValidos(YearMonth mes, int pedidos, LocalDate marcoMaisRecente) {
        List<YearMonth> validos = new ArrayList<>();
        for (int i = pedidos; i >= 1; i--) {
            YearMonth candidato = mes.minusMonths(i);
            if (marcoMaisRecente == null || !marcoMaisRecente.isAfter(candidato.atDay(1))) {
                validos.add(candidato);
            }
        }
        return validos;
    }

    /** O primeiro mês com histórico completo: o do marco, se ele cai no dia 1, ou o seguinte. */
    public static YearMonth completoDesde(LocalDate marcoMaisRecente) {
        YearMonth mes = YearMonth.from(marcoMaisRecente);
        return marcoMaisRecente.getDayOfMonth() == 1 ? mes : mes.plusMonths(1);
    }

    /** A mediana; com quantidade par, a média dos dois do meio, arredondada ao centavo (meio para o par). */
    public static long mediana(List<Long> valores) {
        if (valores.isEmpty()) {
            throw new IllegalArgumentException("Mediana sem valores.");
        }
        List<Long> ordenados = valores.stream().sorted().toList();
        int meio = ordenados.size() / 2;
        if (ordenados.size() % 2 == 1) {
            return ordenados.get(meio);
        }
        return BigDecimal.valueOf(ordenados.get(meio - 1)).add(BigDecimal.valueOf(ordenados.get(meio)))
                .divide(BigDecimal.TWO, 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** O percentil {@value #PERCENTIL} pelo posto mais próximo; {@code null} com menos de {@value #COMPRAS_MINIMAS} valores. */
    public static Long percentil(List<Long> valores) {
        if (valores.size() < COMPRAS_MINIMAS) {
            return null;
        }
        List<Long> ordenados = valores.stream().sorted().toList();
        int posto = (int) Math.ceil(PERCENTIL * ordenados.size() / 100.0);
        return ordenados.get(posto - 1);
    }

    /** Centavos divididos por dias, ao centavo. */
    static long porDia(long centavos, int dias) {
        return BigDecimal.valueOf(centavos).divide(BigDecimal.valueOf(dias), 0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** O percentual de {@code diferenca} sobre {@code base}; {@code null} se a base é zero. */
    static BigDecimal percentual(long diferenca, long base) {
        return base == 0 ? null : BigDecimal.valueOf(diferenca).multiply(CEM)
                .divide(BigDecimal.valueOf(Math.abs(base)), ESCALA_PERCENTUAL, RoundingMode.HALF_EVEN);
    }

    /**
     * O típico do mesmo recorte. Existe só com {@value #MESES_MINIMOS} meses válidos ou mais.
     *
     * @param restante a mediana do que os meses de referência gastaram depois do dia de corte
     * @param percentual da diferença sobre o típico; {@code null} se o típico é zero
     */
    public record Tipico(long acumulado, long ritmoDiario, long restante, long diferenca, BigDecimal percentual) {}

    /**
     * Uma linha dos responsáveis: o atual e o típico até o mesmo dia, e quanto ela contribui.
     *
     * @param chave o id da categoria ou a contraparte normalizada; {@code null} junta os gastos sem contraparte
     */
    public record Contribuicao(String chave, String nome, long atual, long tipico, long contribuicao) {}

    /**
     * Uma compra do mês acima do percentil da categoria nos meses de referência.
     *
     * @param excesso o quanto passou do percentil: é a contribuição dela
     */
    public record CompraAtipica(UUID lancamento, String codigo, LocalDate data, UUID categoria, String descricao, long valor,
            long percentil, long excesso) {}

    /**
     * A tendência de uma categoria nos {@value CalculoSituacao#MESES_TENDENCIA} meses fechados.
     *
     * @param variacoes o percentual de cada mês sobre o anterior; {@code null} onde o anterior é zero
     * @param subindo a mesma regra de {@code categoria_subindo}
     */
    public record TendenciaCategoria(UUID categoria, List<Long> meses, List<BigDecimal> variacoes, boolean subindo) {

        public TendenciaCategoria {
            meses = List.copyOf(meses);
            variacoes = java.util.Collections.unmodifiableList(new ArrayList<>(variacoes));
        }
    }

    /**
     * Um mês de referência.
     *
     * @param ateODia o gasto até o mesmo dia do recorte
     * @param total o gasto do mês inteiro: com os fixos, é a soma da quebra por categoria de {@code resumo_periodo}
     */
    public record MesReferencia(YearMonth mes, long ateODia, long total) {}

    /**
     * @param mesesReferencia os meses válidos, do mais antigo ao mais recente, com ou sem típico
     * @param ritmoDiario o acumulado dividido pelos dias decorridos
     * @param mantidoRitmoAtual o ritmo diário atual vezes os dias do mês; {@code null} em mês fechado
     * @param acumuladoMaisRestanteTipico o acumulado mais o restante típico; {@code null} sem típico ou em mês fechado
     * @param tipico {@code null} com menos de {@value #MESES_MINIMOS} meses válidos
     */
    public record Resultado(Recorte recorte, List<MesReferencia> mesesReferencia, long acumulado, long ritmoDiario, Long mantidoRitmoAtual,
            Long acumuladoMaisRestanteTipico, Tipico tipico, List<Contribuicao> categorias, List<Contribuicao> contrapartes,
            List<CompraAtipica> comprasAtipicas) {

        public Resultado {
            mesesReferencia = List.copyOf(mesesReferencia);
            categorias = List.copyOf(categorias);
            contrapartes = List.copyOf(contrapartes);
            comprasAtipicas = List.copyOf(comprasAtipicas);
        }
    }

    /**
     * O ritmo do mês.
     *
     * @param doMes os gastos do mês analisado, inteiro
     * @param referencia os gastos de cada mês de referência válido, inteiros
     * @param incluirFixos sem isso, os fixos ficam fora de tudo (decisão 5)
     */
    public static Resultado calcular(Recorte recorte, List<Gasto> doMes, Map<YearMonth, List<Gasto>> referencia, boolean incluirFixos) {
        List<Gasto> atuais = ate(filtrar(doMes, incluirFixos), recorte.corte());
        long acumulado = soma(atuais);
        long ritmo = porDia(acumulado, recorte.dia());
        Long mantido = recorte.emCurso() ? BigDecimal.valueOf(acumulado).multiply(BigDecimal.valueOf(recorte.diasDoMes()))
                .divide(BigDecimal.valueOf(recorte.dia()), 0, RoundingMode.HALF_EVEN).longValueExact() : null;
        Map<YearMonth, List<Gasto>> ref = new LinkedHashMap<>();
        new TreeSet<>(referencia.keySet()).forEach(m -> ref.put(m, filtrar(referencia.get(m), incluirFixos)));
        List<MesReferencia> meses = new ArrayList<>();
        ref.forEach((mes, gastos) -> meses.add(new MesReferencia(mes, soma(ate(gastos, recorte.corteEm(mes))), soma(gastos))));
        if (meses.size() < MESES_MINIMOS) {
            return new Resultado(recorte, meses, acumulado, ritmo, mantido, null, null, List.of(), List.of(), List.of());
        }

        List<Long> acumulados = meses.stream().map(MesReferencia::ateODia).toList();
        List<Long> restantes = meses.stream().map(m -> m.total() - m.ateODia()).toList();
        long tipico = mediana(acumulados);
        long restante = mediana(restantes);
        long diferenca = acumulado - tipico;
        Tipico t = new Tipico(tipico, porDia(tipico, recorte.dia()), restante, diferenca, percentual(diferenca, tipico));
        Long projecao = recorte.emCurso() ? acumulado + restante : null;

        Map<YearMonth, List<Gasto>> refAteCorte = new LinkedHashMap<>();
        ref.forEach((mes, gastos) -> refAteCorte.put(mes, ate(gastos, recorte.corteEm(mes))));
        List<Contribuicao> categorias = responsaveis(atuais, refAteCorte, g -> g.categoria().toString(), g -> null, diferenca);
        List<Contribuicao> contrapartes = responsaveis(atuais, refAteCorte, Gasto::chaveContraparte, Gasto::contraparte, diferenca);
        return new Resultado(recorte, meses, acumulado, ritmo, mantido, projecao, t, categorias, contrapartes,
                atipicas(atuais, ref.values()));
    }

    /**
     * A tendência das categorias nos meses fechados, com os mesmos valores de {@code categoria_subindo}: a soma
     * de saídas por categoria de cada mês, do mais antigo ao mais recente (decisão 7).
     *
     * @param saidasPorMes para cada categoria, o gasto de cada mês na ordem dos meses
     */
    public static List<TendenciaCategoria> tendencia(Map<UUID, List<Long>> saidasPorMes) {
        List<TendenciaCategoria> linhas = new ArrayList<>();
        saidasPorMes.forEach((categoria, valores) -> {
            List<BigDecimal> variacoes = new ArrayList<>();
            for (int i = 1; i < valores.size(); i++) {
                variacoes.add(percentual(valores.get(i) - valores.get(i - 1), valores.get(i - 1)));
            }
            boolean subindo = CalculoSituacao.categoriaSubindo(valores).isPresent();
            linhas.add(new TendenciaCategoria(categoria, valores, variacoes, subindo));
        });
        linhas.sort(Comparator.comparing((TendenciaCategoria l) -> !l.subindo())
                .thenComparing(l -> l.meses().isEmpty() ? 0L : l.meses().get(l.meses().size() - 1), Comparator.reverseOrder())
                .thenComparing(l -> l.categoria().toString()));
        return linhas;
    }

    /**
     * Os que puxam a diferença para o mesmo lado dela, do maior ao menor. A contribuição é o atual menos a
     * mediana do mesmo grupo nos meses de referência; somadas, não dão a diferença exata, porque a mediana
     * de cada grupo vem de meses diferentes.
     */
    private static List<Contribuicao> responsaveis(List<Gasto> atuais, Map<YearMonth, List<Gasto>> referencia,
            Function<Gasto, String> chave, Function<Gasto, String> nome, long diferenca) {
        if (diferenca == 0) {
            return List.of();
        }
        Map<String, Long> agora = somarPor(atuais, chave);
        Map<String, String> nomes = new HashMap<>();
        atuais.forEach(g -> nomes.putIfAbsent(texto(chave.apply(g)), nome.apply(g)));
        List<Map<String, Long>> porMes = new ArrayList<>();
        referencia.values().forEach(gastos -> {
            porMes.add(somarPor(gastos, chave));
            gastos.forEach(g -> nomes.putIfAbsent(texto(chave.apply(g)), nome.apply(g)));
        });
        java.util.Set<String> chaves = new TreeSet<>(agora.keySet());
        porMes.forEach(m -> chaves.addAll(m.keySet()));

        List<Contribuicao> linhas = new ArrayList<>();
        for (String k : chaves) {
            long atual = agora.getOrDefault(k, 0L);
            long tipico = mediana(porMes.stream().map(m -> m.getOrDefault(k, 0L)).toList());
            long contribuicao = atual - tipico;
            if (Long.signum(contribuicao) == Long.signum(diferenca)) {
                linhas.add(new Contribuicao(k.isEmpty() ? null : k, nomes.get(k), atual, tipico, contribuicao));
            }
        }
        linhas.sort(Comparator.comparing((Contribuicao c) -> Math.abs(c.contribuicao()), Comparator.reverseOrder())
                .thenComparing(c -> texto(c.chave())));
        return linhas.stream().limit(RESPONSAVEIS).toList();
    }

    /** As compras do mês acima do percentil da categoria; uma compra é a parte de um lançamento numa categoria. */
    private static List<CompraAtipica> atipicas(List<Gasto> atuais, java.util.Collection<List<Gasto>> referencia) {
        Map<UUID, List<Long>> historico = new HashMap<>();
        referencia.forEach(gastos -> compras(gastos).values().forEach(c -> historico.computeIfAbsent(c.categoria(), k -> new ArrayList<>())
                .add(c.centavos())));
        List<CompraAtipica> linhas = new ArrayList<>();
        compras(atuais).values().forEach(c -> {
            Long limite = percentil(historico.getOrDefault(c.categoria(), List.of()));
            if (limite != null && c.centavos() > limite) {
                linhas.add(new CompraAtipica(c.lancamento(), c.codigo(), c.data(), c.categoria(), c.descricao(), c.centavos(), limite,
                        c.centavos() - limite));
            }
        });
        linhas.sort(Comparator.comparing(CompraAtipica::excesso, Comparator.reverseOrder()).thenComparing(CompraAtipica::data)
                .thenComparing(c -> texto(c.codigo())));
        return linhas.stream().limit(RESPONSAVEIS).toList();
    }

    /** Junta as partes do mesmo lançamento na mesma categoria, na ordem em que aparecem. */
    private static Map<String, Gasto> compras(List<Gasto> gastos) {
        Map<String, Gasto> compras = new LinkedHashMap<>();
        for (Gasto g : gastos) {
            compras.merge(g.lancamento() + "/" + g.categoria(), g, (a, b) -> new Gasto(a.lancamento(), a.codigo(), a.data(),
                    a.categoria(), a.chaveContraparte(), a.contraparte(), a.descricao(), a.centavos() + b.centavos(), a.fixo()));
        }
        return compras;
    }

    private static List<Gasto> filtrar(List<Gasto> gastos, boolean incluirFixos) {
        return gastos == null ? List.of() : incluirFixos ? List.copyOf(gastos) : gastos.stream().filter(g -> !g.fixo()).toList();
    }

    private static List<Gasto> ate(List<Gasto> gastos, LocalDate corte) {
        return gastos.stream().filter(g -> !g.data().isAfter(corte)).toList();
    }

    private static long soma(List<Gasto> gastos) {
        return gastos.stream().mapToLong(Gasto::centavos).sum();
    }

    private static Map<String, Long> somarPor(List<Gasto> gastos, Function<Gasto, String> chave) {
        Map<String, Long> somas = new HashMap<>();
        gastos.forEach(g -> somas.merge(texto(chave.apply(g)), g.centavos(), Long::sum));
        return somas;
    }

    private static String texto(String chave) {
        return chave == null ? "" : chave;
    }
}
