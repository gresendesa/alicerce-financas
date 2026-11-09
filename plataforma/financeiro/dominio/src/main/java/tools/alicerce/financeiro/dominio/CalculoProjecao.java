package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * As contas de {@code projetar}: o saldo mês a mês, em visão de caixa, pelo motor de projeção da modelagem.
 * Tudo em centavos da moeda do dono; quem converte em texto é a aplicação.
 *
 * <ul>
 *   <li>o horizonte vai do mês de hoje (de hoje ao último dia) a {@value #HORIZONTE_MAXIMO} meses civis (decisão 1);
 *   <li>cada mês soma as entradas e saídas conhecidas (ocorrências de compromisso), as faturas que vencem nele (o já
 *       lançado no cartão, parcelas inclusive) e o gasto variável estimado: a mediana na estimativa otimista e o
 *       percentil {@value #PERCENTIL_PESSIMISTA} na pessimista (decisão 2);
 *   <li>o gasto variável tem a mesma base de {@code cortes_necessarios}: sem pagamentos de compromisso e sem compras
 *       atípicas. A parte que, nos meses de referência, foi para cada cartão vai para a fatura em que venceria,
 *       comprada por igual ao longo dos dias do mês (decisão 3);
 *   <li>o que vence antes de hoje e está em aberto conta no mês corrente; o que vence depois do horizonte fica fora;
 *   <li>uma hipótese só soma fluxos que a aplicação já calculou pelo código da escrita, ou muda a renda ou uma
 *       categoria a partir de um mês (decisões 5 e 6);
 *   <li>com menos de {@value #MESES_MINIMOS} meses de referência, não há estimativa de gasto variável: os saldos saem
 *       sem ele (decisão 8);
 *   <li>os aportes planejados em metas saem do saldo, e cada meta ganha a data projetada: o primeiro mês em que o
 *       progresso de hoje mais os aportes planejados alcança o alvo;
 *   <li>cada mês tem a renda (as entradas; no corrente, também as já recebidas), as parcelas (de compras parceladas nas
 *       faturas e de compromissos parcelados) e o comprometimento, parcelas sobre renda; as parcelas são uma visão dentro
 *       das faturas e saídas, e não mudam o saldo; com as regras declaradas, saem as violações.
 * </ul>
 */
public final class CalculoProjecao {

    public static final int HORIZONTE_PADRAO = 6;
    public static final int HORIZONTE_MAXIMO = 24;
    public static final int CENARIOS_MAXIMOS = 3;
    /** Com menos meses de referência que isto, não há estimativa de gasto variável. */
    public static final int MESES_MINIMOS = CalculoRitmo.MESES_MINIMOS;
    /** O percentil da estimativa pessimista: a soma dos percentis de cada categoria superestima de propósito. */
    public static final int PERCENTIL_PESSIMISTA = CalculoCortes.PERCENTIL_CONSERVADOR;

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private CalculoProjecao() {}

    /** Onde um fluxo conhecido entra no mês. */
    public enum Coluna {
        /** Ocorrência de renda, ou outra entrada conhecida. */
        ENTRADA,
        /** Ocorrência a pagar, ou compra fora do cartão. */
        SAIDA,
        /** O que vence numa fatura de cartão: o restante das gravadas e as parcelas de uma compra simulada. */
        FATURA,
        /** Um aporte planejado numa meta: sai do saldo e soma ao progresso dela. */
        APORTE
    }

    /**
     * Um fluxo com data. Antes de hoje é atrasado e conta no mês corrente; depois do horizonte, fica fora.
     *
     * @param centavos sem sinal: a coluna diz se entra ou sai
     * @param meta só em {@link Coluna#APORTE}: a meta que recebe o aporte
     * @param parcela é parcela de compra parcelada ou de compromisso parcelado: conta também nas parcelas do mês
     */
    public record Fluxo(Coluna coluna, LocalDate data, long centavos, UUID meta, boolean parcela) {

        public Fluxo {
            Objects.requireNonNull(coluna, "coluna");
            Objects.requireNonNull(data, "data");
            if (centavos < 0) {
                throw new IllegalArgumentException("Fluxo sem sinal");
            }
            if ((coluna == Coluna.APORTE) != (meta != null)) {
                throw new IllegalArgumentException("Só aporte tem meta");
            }
            if (parcela && coluna != Coluna.FATURA && coluna != Coluna.SAIDA) {
                throw new IllegalArgumentException("Parcela é saída ou fatura");
            }
        }

        public Fluxo(Coluna coluna, LocalDate data, long centavos, UUID meta) {
            this(coluna, data, centavos, meta, false);
        }

        public Fluxo(Coluna coluna, LocalDate data, long centavos) {
            this(coluna, data, centavos, null, false);
        }

        /** Uma parcela: a de uma compra parcelada simulada na fatura, ou a de um compromisso parcelado simulado. */
        public static Fluxo parcela(Coluna coluna, LocalDate data, long centavos) {
            return new Fluxo(coluna, data, centavos, null, true);
        }
    }

    /**
     * Uma meta em andamento, como está hoje.
     *
     * @param progresso aportes menos resgates registrados
     * @param dataAlvo nula se o dono não marcou data
     * @param protegida declarada em {@code meta_protegida}: um cenário não pode atrasá-la
     */
    public record MetaBase(UUID id, long progresso, long alvo, LocalDate dataAlvo, boolean protegida) {

        public MetaBase {
            Objects.requireNonNull(id, "id");
        }

        public MetaBase(UUID id, long progresso, long alvo, LocalDate dataAlvo) {
            this(id, progresso, alvo, dataAlvo, false);
        }
    }

    /**
     * As regras declaradas e o que as parcelas e a renda precisam além dos fluxos.
     *
     * @param reserva a reserva mínima em centavos; nula sem declaração ou sem valor
     * @param teto o teto de parcelas, em % da renda; nulo sem declaração
     * @param parcelas por mês, as parcelas já conhecidas: de compras parceladas que vencem nas faturas do mês e de ocorrências
     *     de compromisso parcelado, pagas ou não; não mudam o saldo
     * @param rendaRecebida as entradas já recebidas no mês corrente, que os fluxos em aberto não trazem
     */
    public record Perfil(Long reserva, BigDecimal teto, Map<YearMonth, Long> parcelas, long rendaRecebida) {

        public static final Perfil VAZIO = new Perfil(null, null, Map.of(), 0);

        public Perfil {
            parcelas = Map.copyOf(parcelas);
        }
    }

    /**
     * O gasto variável de uma categoria, base de todos os meses.
     *
     * @param totais o gasto do mês inteiro em cada mês de referência, sem fixos nem compras atípicas; zero onde não gastou
     * @param gastoAteHoje o gasto variável do mês corrente até hoje
     * @param noCartao a parte dos totais gasta em cada cartão, somada nos meses de referência
     */
    public record Categoria(UUID id, List<Long> totais, long gastoAteHoje, Map<UUID, Long> noCartao) {

        public Categoria {
            Objects.requireNonNull(id, "id");
            totais = List.copyOf(totais);
            noCartao = Map.copyOf(new TreeMap<>(noCartao));
            long total = totais.stream().mapToLong(Long::longValue).sum();
            if (noCartao.values().stream().mapToLong(Long::longValue).sum() > total) {
                throw new IllegalArgumentException("Parte no cartão maior que o total");
            }
        }

        long totalReferencia() {
            return totais.stream().mapToLong(Long::longValue).sum();
        }
    }

    /** Quando vence a fatura de uma compra feita num dia, num cartão: a mesma regra de {@code registrar_gasto}. */
    @FunctionalInterface
    public interface Vencimentos {
        LocalDate vencimento(UUID cartao, LocalDate compra);
    }

    /** Uma hipótese já resolvida pela aplicação. */
    public sealed interface Hipotese permits Fluxos, MudancaRenda, MudancaCategoria {}

    /** Uma compra ou um novo compromisso: os fluxos que a escrita real geraria, sem gravar nada. */
    public record Fluxos(List<Fluxo> fluxos) implements Hipotese {

        public Fluxos {
            fluxos = List.copyOf(fluxos);
        }
    }

    /**
     * A renda muda a partir de um mês, todo mês do horizonte.
     *
     * @param centavos com sinal: negativo é perda de renda
     */
    public record MudancaRenda(long centavos, YearMonth de) implements Hipotese {

        public MudancaRenda {
            Objects.requireNonNull(de, "de");
        }
    }

    /**
     * O gasto variável das categorias muda em {@code percentual} a partir de um mês. No mês corrente, muda o restante.
     *
     * @param categorias a categoria e as subcategorias dela
     * @param percentual com sinal: {@code -20} é gastar um quinto a menos
     */
    public record MudancaCategoria(Set<UUID> categorias, BigDecimal percentual, YearMonth de) implements Hipotese {

        public MudancaCategoria {
            categorias = Set.copyOf(categorias);
            Objects.requireNonNull(percentual, "percentual");
            Objects.requireNonNull(de, "de");
            if (percentual.compareTo(CEM.negate()) < 0) {
                throw new IllegalArgumentException("Percentual abaixo de -100");
            }
        }
    }

    /**
     * O que vale para a linha de base e para todos os cenários.
     *
     * @param horizonte de 1 a {@value #HORIZONTE_MAXIMO} meses, o corrente inclusive
     * @param saldoPartida a soma das contas líquidas na moeda do dono, hoje
     * @param fluxos as ocorrências de compromisso em aberto e o restante das faturas gravadas
     * @param mesesReferencia quantos meses de referência valem; cada categoria traz um valor por mês
     * @param piso o saldo mínimo declarado para o fim de cada mês; nulo sem declaração
     * @param metas as metas em andamento, para a data projetada
     */
    public record Base(LocalDate hoje, int horizonte, long saldoPartida, List<Fluxo> fluxos, List<Categoria> categorias, int mesesReferencia,
            Long piso, Vencimentos vencimentos, List<MetaBase> metas, Perfil perfil) {

        public Base(LocalDate hoje, int horizonte, long saldoPartida, List<Fluxo> fluxos, List<Categoria> categorias, int mesesReferencia,
                Long piso, Vencimentos vencimentos) {
            this(hoje, horizonte, saldoPartida, fluxos, categorias, mesesReferencia, piso, vencimentos, List.of());
        }

        public Base(LocalDate hoje, int horizonte, long saldoPartida, List<Fluxo> fluxos, List<Categoria> categorias, int mesesReferencia,
                Long piso, Vencimentos vencimentos, List<MetaBase> metas) {
            this(hoje, horizonte, saldoPartida, fluxos, categorias, mesesReferencia, piso, vencimentos, metas, Perfil.VAZIO);
        }

        public Base {
            metas = List.copyOf(metas);
            Objects.requireNonNull(perfil, "perfil");
            Objects.requireNonNull(hoje, "hoje");
            Objects.requireNonNull(vencimentos, "vencimentos");
            if (horizonte < 1 || horizonte > HORIZONTE_MAXIMO) {
                throw new IllegalArgumentException("Horizonte fora de 1 a " + HORIZONTE_MAXIMO);
            }
            fluxos = List.copyOf(fluxos);
            categorias = List.copyOf(categorias);
            for (Categoria c : categorias) {
                if (c.totais().size() != mesesReferencia) {
                    throw new IllegalArgumentException("Categoria com " + c.totais().size() + " meses, esperados " + mesesReferencia);
                }
            }
        }

        /** Há estimativa de gasto variável. */
        public boolean historicoSuficiente() {
            return mesesReferencia >= MESES_MINIMOS;
        }

        public YearMonth primeiroMes() {
            return YearMonth.from(hoje);
        }

        public YearMonth ultimoMes() {
            return primeiroMes().plusMonths(horizonte - 1L);
        }

        /** O último dia do horizonte. */
        public LocalDate fim() {
            return ultimoMes().atEndOfMonth();
        }
    }

    /**
     * Um mês projetado.
     *
     * @param entradas as conhecidas e as mudanças de renda
     * @param saidas as ocorrências a pagar e as compras fora do cartão
     * @param faturas o que vence nas faturas no mês, sem o gasto variável estimado
     * @param aportes os aportes planejados em metas no mês
     * @param variavelP50 o gasto variável pela mediana, com a parte do cartão no mês em que vence; nulo sem estimativa
     * @param variavelP80 o mesmo pelo percentil 80; nulo sem estimativa
     * @param renda as entradas do mês; no corrente, mais as já recebidas
     * @param parcelas as parcelas que vencem no mês, uma visão dentro de faturas e saídas
     * @param comprometimento parcelas sobre renda, em %, com duas casas; nulo sem renda
     */
    public record Mes(YearMonth mes, long inicialP50, long inicialP80, long entradas, long saidas, long faturas, long aportes,
            Long variavelP50, Long variavelP80, long finalP50, long finalP80, long renda, long parcelas, BigDecimal comprometimento) {}

    /**
     * Quando uma meta chega no alvo pelo progresso de hoje mais os aportes planejados.
     *
     * @param aportado os aportes planejados nela dentro do horizonte
     * @param mes o primeiro mês em que alcança; nulo se não alcança no horizonte
     * @param noPrazo o mês projetado não passa do mês da data alvo; nulo sem data alvo ou sem mês projetado
     */
    public record DataMeta(UUID meta, long aportado, YearMonth mes, Boolean noPrazo) {}

    /** O menor saldo final do horizonte e o primeiro mês em que ele acontece. */
    public record Menor(long centavos, YearMonth mes) {}

    /**
     * O mês em que as faturas mais pesam sobre o que entra.
     *
     * @param percentual faturas sobre entradas, com duas casas
     */
    public record Pico(YearMonth mes, long faturas, long entradas, BigDecimal percentual) {}

    /** O mês em que as parcelas mais pesam sobre a renda. @param percentual o comprometimento, com duas casas */
    public record PicoParcelas(YearMonth mes, long parcelas, long renda, BigDecimal percentual) {}

    /** As regras declaradas que um cenário pode violar. */
    public enum Regra {
        PISO_PERIODO,
        RESERVA_MINIMA,
        TETO_PARCELAS,
        META_PROTEGIDA;

        public String codigo() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Uma regra declarada violada num mês, com os números que mostram por quê. Pelo lado prudente: o saldo final p80.
     *
     * @param saldoFinalP80 em piso, reserva e meta: o saldo final pessimista do mês
     * @param limite em piso e reserva, o declarado; em meta, o piso (zero sem piso) mais o aporte mensal necessário dela
     * @param comprometimento em teto: as parcelas sobre a renda do mês
     * @param meta em meta protegida: qual
     */
    public record Violacao(Regra regra, YearMonth mes, Long saldoFinalP80, Long limite, Long parcelas, Long renda, BigDecimal comprometimento,
            BigDecimal teto, UUID meta, Long aporteNecessario) {}

    /**
     * @param abaixoPisoP50 os meses com saldo final abaixo do piso na estimativa otimista; vazio sem piso
     * @param pico nulo se nenhum mês tem faturas e entradas
     * @param metas uma por meta em andamento, na ordem da base
     * @param abaixoReservaP50 os meses com saldo final abaixo da reserva mínima; vazio sem reserva
     * @param picoParcelas nulo se nenhum mês tem parcelas e renda
     * @param mesesSemRenda os meses sem renda, em que o comprometimento não é avaliado
     * @param violacoes as regras declaradas violadas, por regra e mês
     */
    public record Resultado(List<Mes> meses, Menor menorP50, Menor menorP80, List<YearMonth> abaixoPisoP50, List<YearMonth> abaixoPisoP80,
            Pico pico, List<DataMeta> metas, List<YearMonth> abaixoReservaP50, List<YearMonth> abaixoReservaP80, PicoParcelas picoParcelas,
            List<YearMonth> mesesSemRenda, List<Violacao> violacoes) {

        public Resultado {
            metas = List.copyOf(metas);
            abaixoReservaP50 = List.copyOf(abaixoReservaP50);
            abaixoReservaP80 = List.copyOf(abaixoReservaP80);
            mesesSemRenda = List.copyOf(mesesSemRenda);
            violacoes = List.copyOf(violacoes);
            meses = List.copyOf(meses);
            abaixoPisoP50 = List.copyOf(abaixoPisoP50);
            abaixoPisoP80 = List.copyOf(abaixoPisoP80);
        }

        public Mes ultimo() {
            return meses.get(meses.size() - 1);
        }
    }

    /** A diferença de um mês do cenário para a linha de base: cenário menos base. */
    public record DiferencaMes(YearMonth mes, long finalP50, long finalP80) {}

    /** A diferença de um cenário para a linha de base: cenário menos base. */
    public record Diferenca(List<DiferencaMes> meses, long finalP50, long finalP80, long menorP50, long menorP80) {

        public Diferenca {
            meses = List.copyOf(meses);
        }
    }

    /**
     * Monta as categorias do gasto variável como em {@code cortes_necessarios}: sem fixos e sem as compras atípicas dos
     * meses de referência, pela mesma regra; cada compra que foi num cartão conta, também, na parte dele.
     *
     * @param doMes os gastos do mês corrente; os de depois de hoje ficam fora
     * @param referencia os gastos de cada mês de referência válido, inteiros; mês sem gasto entra com lista vazia
     * @param cartaoDoLancamento o cartão de cada lançamento pago no crédito
     * @return por categoria, na ordem do id
     */
    public static List<Categoria> categorias(LocalDate hoje, List<CalculoRitmo.Gasto> doMes, Map<YearMonth, List<CalculoRitmo.Gasto>> referencia,
            Map<UUID, UUID> cartaoDoLancamento) {
        Map<YearMonth, List<CalculoRitmo.Gasto>> ref = CalculoCortes.variaveis(referencia);
        Map<UUID, Long> limites = CalculoCortes.limitesAtipicos(ref);
        List<YearMonth> meses = List.copyOf(ref.keySet());
        TreeMap<UUID, long[]> totais = new TreeMap<>();
        Map<UUID, Map<UUID, Long>> noCartao = new HashMap<>();
        for (int i = 0; i < meses.size(); i++) {
            for (CalculoRitmo.Gasto g : CalculoCortes.porCompra(ref.get(meses.get(i))).values()) {
                Long limite = limites.get(g.categoria());
                if (limite != null && g.centavos() > limite) {
                    continue;
                }
                totais.computeIfAbsent(g.categoria(), k -> new long[meses.size()])[i] += g.centavos();
                UUID cartao = cartaoDoLancamento.get(g.lancamento());
                if (cartao != null) {
                    noCartao.computeIfAbsent(g.categoria(), k -> new HashMap<>()).merge(cartao, g.centavos(), Long::sum);
                }
            }
        }
        TreeMap<UUID, Long> gastos = new TreeMap<>();
        doMes.stream().filter(g -> !g.fixo() && !g.data().isAfter(hoje)).forEach(g -> gastos.merge(g.categoria(), g.centavos(), Long::sum));
        TreeSet<UUID> ids = new TreeSet<>(totais.keySet());
        ids.addAll(gastos.keySet());
        List<Categoria> lista = new ArrayList<>();
        for (UUID id : ids) {
            long[] t = totais.getOrDefault(id, new long[meses.size()]);
            lista.add(new Categoria(id, java.util.Arrays.stream(t).boxed().toList(), gastos.getOrDefault(id, 0L),
                    noCartao.getOrDefault(id, Map.of())));
        }
        return lista;
    }

    /** A projeção da linha de base mais as hipóteses de um cenário; sem hipóteses, a linha de base. */
    public static Resultado projetar(Base b, List<Hipotese> hipoteses) {
        int n = b.horizonte();
        YearMonth primeiro = b.primeiroMes();
        long[] entradas = new long[n];
        long[] saidas = new long[n];
        long[] faturas = new long[n];
        long[] aportes = new long[n];
        long[] parcelas = new long[n];
        for (int i = 0; i < n; i++) {
            parcelas[i] = b.perfil().parcelas().getOrDefault(primeiro.plusMonths(i), 0L);
        }
        Map<UUID, long[]> porMeta = new HashMap<>();
        List<Fluxo> fluxos = new ArrayList<>(b.fluxos());
        List<MudancaCategoria> categorias = new ArrayList<>();
        for (Hipotese h : hipoteses) {
            switch (h) {
                case Fluxos f -> fluxos.addAll(f.fluxos());
                case MudancaRenda r -> {
                    for (int i = indice(b, r.de()); i >= 0 && i < n; i++) {
                        entradas[i] = Math.addExact(entradas[i], r.centavos());
                    }
                }
                case MudancaCategoria c -> categorias.add(c);
            }
        }
        for (Fluxo f : fluxos) {
            int i = f.data().isBefore(b.hoje()) ? 0 : indice(b, YearMonth.from(f.data()));
            if (i < 0 || i >= n) {
                continue;
            }
            long[] coluna = switch (f.coluna()) {
                case ENTRADA -> entradas;
                case SAIDA -> saidas;
                case FATURA -> faturas;
                case APORTE -> aportes;
            };
            coluna[i] = Math.addExact(coluna[i], f.centavos());
            if (f.parcela()) {
                parcelas[i] = Math.addExact(parcelas[i], f.centavos());
            }
            if (f.meta() != null) {
                long[] daMeta = porMeta.computeIfAbsent(f.meta(), k -> new long[n]);
                daMeta[i] = Math.addExact(daMeta[i], f.centavos());
            }
        }
        long[] p50 = b.historicoSuficiente() ? variavel(b, CalculoCortes.Estimativa.P50, categorias) : null;
        long[] p80 = b.historicoSuficiente() ? variavel(b, CalculoCortes.Estimativa.P80, categorias) : null;

        List<Mes> meses = new ArrayList<>();
        long saldo50 = b.saldoPartida();
        long saldo80 = b.saldoPartida();
        for (int i = 0; i < n; i++) {
            long conhecido = Math.subtractExact(Math.subtractExact(Math.subtractExact(entradas[i], saidas[i]), faturas[i]), aportes[i]);
            long fim50 = Math.subtractExact(Math.addExact(saldo50, conhecido), p50 == null ? 0 : p50[i]);
            long fim80 = Math.subtractExact(Math.addExact(saldo80, conhecido), p80 == null ? 0 : p80[i]);
            long renda = i == 0 ? Math.addExact(entradas[i], b.perfil().rendaRecebida()) : entradas[i];
            meses.add(new Mes(primeiro.plusMonths(i), saldo50, saldo80, entradas[i], saidas[i], faturas[i], aportes[i], p50 == null ? null : p50[i],
                    p80 == null ? null : p80[i], fim50, fim80, renda, parcelas[i], CalculoPerfil.comprometimento(parcelas[i], renda)));
            saldo50 = fim50;
            saldo80 = fim80;
        }
        Long reserva = b.perfil().reserva();
        return new Resultado(meses, menor(meses, Mes::finalP50), menor(meses, Mes::finalP80), abaixo(meses, b.piso(), Mes::finalP50),
                abaixo(meses, b.piso(), Mes::finalP80), pico(meses), datasDasMetas(b, porMeta), abaixo(meses, reserva, Mes::finalP50),
                abaixo(meses, reserva, Mes::finalP80), picoParcelas(meses), meses.stream().filter(m -> m.renda() <= 0).map(Mes::mes).toList(),
                violacoes(b, meses));
    }

    /** As regras declaradas violadas, pelo saldo final p80, na ordem das regras e dos meses. */
    private static List<Violacao> violacoes(Base b, List<Mes> meses) {
        List<Violacao> lista = new ArrayList<>();
        if (b.piso() != null) {
            meses.stream().filter(m -> m.finalP80() < b.piso()).forEach(m -> lista.add(new Violacao(Regra.PISO_PERIODO, m.mes(), m.finalP80(),
                    b.piso(), null, null, null, null, null, null)));
        }
        Long reserva = b.perfil().reserva();
        if (reserva != null) {
            meses.stream().filter(m -> m.finalP80() < reserva).forEach(m -> lista.add(new Violacao(Regra.RESERVA_MINIMA, m.mes(), m.finalP80(),
                    reserva, null, null, null, null, null, null)));
        }
        BigDecimal teto = b.perfil().teto();
        if (teto != null) {
            meses.stream().filter(m -> CalculoPerfil.acimaDoTeto(m.comprometimento(), teto)).forEach(m -> lista.add(new Violacao(
                    Regra.TETO_PARCELAS, m.mes(), null, null, m.parcelas(), m.renda(), m.comprometimento(), teto, null, null)));
        }
        long piso = b.piso() == null ? 0 : b.piso();
        for (MetaBase meta : b.metas()) {
            if (!meta.protegida() || meta.dataAlvo() == null || meta.dataAlvo().isBefore(b.hoje())) {
                continue;
            }
            long falta = Math.max(0, Math.subtractExact(meta.alvo(), meta.progresso()));
            if (falta == 0) {
                continue;
            }
            long necessario = CalculoMeta.aporteMensal(falta, CalculoMeta.mesesAte(b.hoje(), meta.dataAlvo()));
            long limite = Math.addExact(piso, necessario);
            YearMonth ate = YearMonth.from(meta.dataAlvo());
            meses.stream().filter(m -> !m.mes().isAfter(ate) && m.finalP80() < limite).forEach(m -> lista.add(new Violacao(Regra.META_PROTEGIDA,
                    m.mes(), m.finalP80(), limite, null, null, null, null, meta.id(), necessario)));
        }
        return lista;
    }

    private static PicoParcelas picoParcelas(List<Mes> meses) {
        PicoParcelas pico = null;
        for (Mes m : meses) {
            if (m.parcelas() <= 0 || m.comprometimento() == null) {
                continue;
            }
            if (pico == null || m.comprometimento().compareTo(pico.percentual()) > 0) {
                pico = new PicoParcelas(m.mes(), m.parcelas(), m.renda(), m.comprometimento());
            }
        }
        return pico;
    }

    /** Por meta, o primeiro mês em que o progresso mais os aportes planejados até ele alcança o alvo. */
    private static List<DataMeta> datasDasMetas(Base b, Map<UUID, long[]> porMeta) {
        List<DataMeta> lista = new ArrayList<>();
        for (MetaBase m : b.metas()) {
            long[] daMeta = porMeta.getOrDefault(m.id(), new long[b.horizonte()]);
            long acumulado = m.progresso();
            YearMonth alcanca = null;
            for (int i = 0; i < daMeta.length; i++) {
                acumulado = Math.addExact(acumulado, daMeta[i]);
                if (alcanca == null && acumulado >= m.alvo()) {
                    alcanca = b.primeiroMes().plusMonths(i);
                }
            }
            long aportado = java.util.Arrays.stream(daMeta).sum();
            Boolean noPrazo = alcanca == null || m.dataAlvo() == null ? null : !alcanca.isAfter(YearMonth.from(m.dataAlvo()));
            lista.add(new DataMeta(m.id(), aportado, alcanca, noPrazo));
        }
        return lista;
    }

    /** Cenário menos linha de base, mês a mês, no fim do horizonte e no menor saldo. */
    public static Diferenca diferenca(Resultado base, Resultado cenario) {
        if (base.meses().size() != cenario.meses().size()) {
            throw new IllegalArgumentException("Horizontes diferentes");
        }
        List<DiferencaMes> meses = new ArrayList<>();
        for (int i = 0; i < base.meses().size(); i++) {
            Mes a = base.meses().get(i);
            Mes c = cenario.meses().get(i);
            meses.add(new DiferencaMes(a.mes(), Math.subtractExact(c.finalP50(), a.finalP50()), Math.subtractExact(c.finalP80(), a.finalP80())));
        }
        return new Diferenca(meses, Math.subtractExact(cenario.ultimo().finalP50(), base.ultimo().finalP50()),
                Math.subtractExact(cenario.ultimo().finalP80(), base.ultimo().finalP80()),
                Math.subtractExact(cenario.menorP50().centavos(), base.menorP50().centavos()),
                Math.subtractExact(cenario.menorP80().centavos(), base.menorP80().centavos()));
    }

    /** O índice do mês no horizonte; negativo antes dele, {@code n} ou mais depois. */
    private static int indice(Base b, YearMonth mes) {
        return Math.toIntExact(b.primeiroMes().until(mes, java.time.temporal.ChronoUnit.MONTHS));
    }

    /**
     * O gasto variável de cada mês numa estimativa. No mês corrente, o restante: o típico menos o já gasto, nunca
     * negativo. A parte do cartão sai do mês em que é gasta e entra no mês em que a fatura vence.
     */
    private static long[] variavel(Base b, CalculoCortes.Estimativa estimativa, List<MudancaCategoria> mudancas) {
        int n = b.horizonte();
        long[] porMes = new long[n];
        // Por mês de gasto e cartão, o que vai para fatura: depois espalhado pelos dias e posto no vencimento.
        List<Map<UUID, Long>> noCartao = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            noCartao.add(new TreeMap<>());
        }
        for (Categoria c : b.categorias()) {
            long tipico = c.totais().isEmpty() ? 0 : estimativa == CalculoCortes.Estimativa.P80
                    ? CalculoCortes.percentil(c.totais(), PERCENTIL_PESSIMISTA) : CalculoRitmo.mediana(c.totais());
            long total = c.totalReferencia();
            for (int i = 0; i < n; i++) {
                YearMonth mes = b.primeiroMes().plusMonths(i);
                long valor = i == 0 ? Math.max(0, tipico - c.gastoAteHoje()) : tipico;
                for (MudancaCategoria m : mudancas) {
                    if (m.categorias().contains(c.id()) && !mes.isBefore(m.de())) {
                        valor = BigDecimal.valueOf(valor).multiply(CEM.add(m.percentual())).divide(CEM, 0, RoundingMode.HALF_EVEN).longValueExact();
                    }
                }
                long credito = 0;
                if (total > 0) {
                    for (Map.Entry<UUID, Long> parte : c.noCartao().entrySet()) {
                        long noMes = BigDecimal.valueOf(valor).multiply(BigDecimal.valueOf(parte.getValue()))
                                .divide(BigDecimal.valueOf(total), 0, RoundingMode.DOWN).longValueExact();
                        noCartao.get(i).merge(parte.getKey(), noMes, Long::sum);
                        credito += noMes;
                    }
                }
                porMes[i] = Math.addExact(porMes[i], valor - credito);
            }
        }
        for (int i = 0; i < n; i++) {
            YearMonth mes = b.primeiroMes().plusMonths(i);
            List<LocalDate> dias = dias(b.hoje(), mes, i == 0);
            for (Map.Entry<UUID, Long> parte : noCartao.get(i).entrySet()) {
                long[] porDia = porIgual(parte.getValue(), dias.size());
                for (int d = 0; d < dias.size(); d++) {
                    if (porDia[d] == 0) {
                        continue;
                    }
                    LocalDate vence = b.vencimentos().vencimento(parte.getKey(), dias.get(d));
                    int j = vence.isBefore(b.hoje()) ? 0 : indice(b, YearMonth.from(vence));
                    if (j < n) {
                        porMes[j] = Math.addExact(porMes[j], porDia[d]);
                    }
                }
            }
        }
        return porMes;
    }

    /** Os dias em que o gasto de um mês acontece: no corrente, os de depois de hoje (ou hoje, no último dia). */
    static List<LocalDate> dias(LocalDate hoje, YearMonth mes, boolean corrente) {
        LocalDate de = corrente ? hoje.plusDays(1) : mes.atDay(1);
        List<LocalDate> dias = new ArrayList<>();
        for (LocalDate d = de; !d.isAfter(mes.atEndOfMonth()); d = d.plusDays(1)) {
            dias.add(d);
        }
        return dias.isEmpty() ? List.of(hoje) : dias;
    }

    /** O total em partes iguais; a sobra, um centavo por vez, vai para as primeiras. */
    static long[] porIgual(long centavos, int partes) {
        long[] lista = new long[partes];
        java.util.Arrays.fill(lista, centavos / partes);
        for (int i = 0; i < centavos % partes; i++) {
            lista[i]++;
        }
        return lista;
    }

    private static Menor menor(List<Mes> meses, java.util.function.ToLongFunction<Mes> saldo) {
        Mes menor = meses.get(0);
        for (Mes m : meses) {
            if (saldo.applyAsLong(m) < saldo.applyAsLong(menor)) {
                menor = m;
            }
        }
        return new Menor(saldo.applyAsLong(menor), menor.mes());
    }

    private static List<YearMonth> abaixo(List<Mes> meses, Long piso, java.util.function.ToLongFunction<Mes> saldo) {
        return piso == null ? List.of() : meses.stream().filter(m -> saldo.applyAsLong(m) < piso).map(Mes::mes).toList();
    }

    private static Pico pico(List<Mes> meses) {
        Pico pico = null;
        for (Mes m : meses) {
            if (m.faturas() <= 0 || m.entradas() <= 0) {
                continue;
            }
            BigDecimal percentual = BigDecimal.valueOf(m.faturas()).multiply(CEM).divide(BigDecimal.valueOf(m.entradas()), 2, RoundingMode.HALF_EVEN);
            if (pico == null || percentual.compareTo(pico.percentual()) > 0) {
                pico = new Pico(m.mes(), m.faturas(), m.entradas(), percentual);
            }
        }
        return pico;
    }
}
