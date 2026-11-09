package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.CalculoCaixa;
import tools.alicerce.financeiro.dominio.CalculoPerfil;
import tools.alicerce.financeiro.dominio.CalculoResumo;
import tools.alicerce.financeiro.dominio.CalculoResumo.Grupo;
import tools.alicerce.financeiro.dominio.CalculoRitmo;
import tools.alicerce.financeiro.dominio.CalculoRitmo.Gasto;
import tools.alicerce.financeiro.dominio.CalculoRitmo.Recorte;
import tools.alicerce.financeiro.dominio.CalculoSituacao;
import tools.alicerce.financeiro.dominio.CompraNoCredito;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.FatoPeriodo;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.Quebra;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Monta o ritmo de um mês a partir de parâmetros já resolvidos. Os gastos são os mesmos fatos de
 * {@code resumo_periodo}, do lado da categoria; as contas são todas de {@link CalculoRitmo}. Só lê.
 */
@Component
class CalculoDeRitmo {

    private static final String NOTA_FIXOS = "Sem os fixos: ficam fora os lançamentos que pagam uma ocorrência de compromisso e, na visão "
            + "caixa, as parcelas de compras de meses anteriores. Com incluir_fixos, tudo entra, e o total de cada mês é o de resumo_periodo.";
    private static final String NOTA_FIXOS_INCLUIDOS = "Com os fixos: o total de cada mês é o mesmo de resumo_periodo no mês e na visão.";
    private static final String NOTA_PROJECOES = "mantido_ritmo_atual é o ritmo diário de hoje vezes os dias do mês. acumulado_mais_restante_tipico "
            + "soma ao gasto de hoje o que os meses de referência costumam gastar daqui até o fim; uma compra grande no começo do mês "
            + "pesa menos nela. Cite as duas pelo nome.";
    private static final String NOTA_RESPONSAVEIS = "Cada linha é o gasto de agora menos o típico (mediana) do mesmo grupo até o mesmo dia, só as que "
            + "puxam para o lado da diferença, das maiores para as menores. Somadas, não dão a diferença exata: a mediana de cada grupo vem "
            + "de meses diferentes. Compra atípica é a que passa do percentil 95 da categoria nos meses de referência, só em categoria "
            + "com 20 compras ou mais.";
    private static final String NOTA_VARIABILIDADE = "Cada categoria nos meses de referência, com os mesmos filtros do ritmo: o menor, a "
            + "mediana e o maior mês, e a dispersão, (p75 − p25) ÷ mediana, em %. Quanto maior a dispersão, mais o gasto muda de um mês para "
            + "outro. Da que mais varia para a que menos.";
    private static final String NOTA_POUPANCA = "Poupança observada é (renda − saídas) ÷ renda nos 3 meses fechados antes de hoje, em "
            + "competência, como resumo_periodo: o mesmo número do sinal poupanca_abaixo de situacao, qualquer que seja o mês pedido.";
    private static final String NOTA_TENDENCIA = "Os 3 meses fechados antes do mês, em competência e com os fixos: os mesmos valores de "
            + "categoria_subindo em situacao, qualquer que seja a visão pedida. subindo segue a mesma regra.";

    private final RepositorioLancamentos lancamentos;
    private final RepositorioCartoes cartoes;
    private final RepositorioEstrutura estrutura;
    private final RepositorioCompromissos compromissos;
    private final Enderecos enderecos;
    private final LeituraDoPerfil perfil;

    CalculoDeRitmo(RepositorioLancamentos lancamentos, RepositorioCartoes cartoes, RepositorioEstrutura estrutura,
            RepositorioCompromissos compromissos, Enderecos enderecos, LeituraDoPerfil perfil) {
        this.perfil = perfil;
        this.lancamentos = lancamentos;
        this.cartoes = cartoes;
        this.estrutura = estrutura;
        this.compromissos = compromissos;
        this.enderecos = enderecos;
    }

    RitmoVisto calcular(UUID dono, Currency moeda, ParametrosRitmo p, OffsetDateTime agora) {
        YearMonth mes = YearMonth.parse(p.mes());
        Recorte recorte = Recorte.de(mes, LocalDate.parse(p.hoje()));
        Map<UUID, Conta> contas = estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Function.identity()));
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono);
        Optional<Map.Entry<UUID, LocalDate>> marco = estrutura.marcos(dono).entrySet().stream()
                .filter(m -> contas.containsKey(m.getKey()) && !contas.get(m.getKey()).arquivada())
                .max(Map.Entry.comparingByValue());
        LocalDate marcoMaisRecente = marco.map(Map.Entry::getValue).orElse(null);
        List<YearMonth> validos = CalculoRitmo.mesesValidos(mes, p.mesesReferencia(), marcoMaisRecente);

        YearMonth primeiro = validos.isEmpty() ? mes : validos.get(0);
        Map<YearMonth, List<Gasto>> porMes = gastos(dono, new Periodo(primeiro.atDay(1), mes.atEndOfMonth()), p.visao());
        Map<YearMonth, List<Gasto>> referencia = new LinkedHashMap<>();
        validos.forEach(m -> referencia.put(m, porMes.getOrDefault(m, List.of())));
        CalculoRitmo.Resultado r = CalculoRitmo.calcular(recorte, porMes.getOrDefault(mes, List.of()), referencia, p.incluirFixos());

        List<RitmoVisto.Ressalva> ressalvas = new ArrayList<>();
        if (r.tipico() == null) {
            String desde = marcoMaisRecente == null ? null : CalculoRitmo.completoDesde(marcoMaisRecente).toString();
            ressalvas.add(new RitmoVisto.Ressalva("historico_insuficiente", "Sem típico: só " + validos.size() + " dos " + p.mesesReferencia()
                    + " meses de referência têm histórico completo, e são precisos " + CalculoRitmo.MESES_MINIMOS + "."
                    + (desde == null ? "" : " O histórico está completo desde " + desde + ", porque a conta "
                            + contas.get(marco.get().getKey()).nome() + " só está coberta desde " + marcoMaisRecente + ".")
                    + " Lançar histórico de meses anteriores não adianta: o mês só vale se começa depois do marco, e o histórico não muda o marco."
                    + " Repasse ao dono o acumulado, o ritmo e a projeção pelo ritmo atual, sem inventar um típico.", validos.size(), desde));
        }
        if (marcoMaisRecente != null && marcoMaisRecente.isAfter(mes.atDay(1))) {
            ressalvas.add(new RitmoVisto.Ressalva("mes_parcial", "O próprio mês " + mes + " tem histórico parcial: a conta "
                    + contas.get(marco.get().getKey()).nome() + " só está coberta desde " + marcoMaisRecente
                    + ". O acumulado pode estar abaixo do real.", null, null));
        }
        RitmoVisto.Tendencia tendencia = tendencia(dono, mes, marcoMaisRecente, contas, codigos, moeda, ressalvas);

        Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moeda).texto();
        RitmoVisto.Projecoes projecoes = recorte.emCurso() ? new RitmoVisto.Projecoes(dinheiro.apply(r.mantidoRitmoAtual()),
                r.acumuladoMaisRestanteTipico() == null ? null : dinheiro.apply(r.acumuladoMaisRestanteTipico()), NOTA_PROJECOES) : null;
        RitmoVisto.Tipico tipico = r.tipico() == null ? null : new RitmoVisto.Tipico(dinheiro.apply(r.tipico().acumulado()),
                dinheiro.apply(r.tipico().ritmoDiario()), dinheiro.apply(r.tipico().restante()), dinheiro.apply(r.tipico().diferenca()),
                texto(r.tipico().percentual()), r.mesesReferencia().size(), p.mesesReferencia());
        List<RitmoVisto.MesReferencia> meses = r.mesesReferencia().stream().map(m -> new RitmoVisto.MesReferencia(m.mes().toString(),
                dinheiro.apply(m.ateODia()), dinheiro.apply(m.total()))).toList();
        RitmoVisto.Responsaveis responsaveis = r.tipico() == null ? null : new RitmoVisto.Responsaveis(
                r.categorias().stream().map(c -> categoria(c, contas, codigos, dinheiro)).toList(),
                r.contrapartes().stream().map(c -> new RitmoVisto.Contribuicao(null, null, c.nome() == null ? "Sem contraparte" : c.nome(),
                        dinheiro.apply(c.atual()), dinheiro.apply(c.tipico()), dinheiro.apply(c.contribuicao()))).toList(),
                r.comprasAtipicas().stream().map(c -> new RitmoVisto.CompraAtipica(c.codigo(), url(c.codigo()), c.data().toString(),
                        c.descricao(), nome(c.categoria(), contas), dinheiro.apply(c.valor()), dinheiro.apply(c.percentil()),
                        dinheiro.apply(c.excesso()))).toList(),
                NOTA_RESPONSAVEIS);
        return new RitmoVisto(agora.toString(), p.mes(), p.hoje(), p.visao(),
                CalculoDeResumo.CAIXA.equals(p.visao()) ? CalculoDeResumo.NOTA_CAIXA : null, moeda.getCurrencyCode(), p.incluirFixos(),
                p.incluirFixos() ? NOTA_FIXOS_INCLUIDOS : NOTA_FIXOS, recorte.emCurso(), recorte.dia(), recorte.diasDoMes(),
                dinheiro.apply(r.acumulado()), dinheiro.apply(r.ritmoDiario()), projecoes, tipico, meses, responsaveis, tendencia, ressalvas,
                r.tipico() == null ? null : variabilidade(referencia, p.incluirFixos(), contas, codigos, dinheiro),
                poupanca(dono, moeda, p, marcoMaisRecente, ressalvas, dinheiro));
    }

    /** A variabilidade de cada categoria nos meses de referência, pelos mesmos gastos do típico. */
    private RitmoVisto.Variabilidade variabilidade(Map<YearMonth, List<Gasto>> referencia, boolean incluirFixos, Map<UUID, Conta> contas,
            Map<UUID, String> codigos, Function<Long, String> dinheiro) {
        List<YearMonth> meses = List.copyOf(new java.util.TreeSet<>(referencia.keySet()));
        Map<UUID, long[]> porCategoria = new java.util.TreeMap<>();
        for (int i = 0; i < meses.size(); i++) {
            for (Gasto g : referencia.get(meses.get(i))) {
                if (incluirFixos || !g.fixo()) {
                    porCategoria.computeIfAbsent(g.categoria(), k -> new long[meses.size()])[i] += g.centavos();
                }
            }
        }
        List<RitmoVisto.VariabilidadeCategoria> linhas = new ArrayList<>();
        List<BigDecimal> ordem = new ArrayList<>();
        porCategoria.forEach((categoria, valores) -> {
            List<Long> lista = java.util.Arrays.stream(valores).boxed().toList();
            CalculoPerfil.Variabilidade v = CalculoPerfil.variabilidade(lista);
            String codigo = codigos.get(categoria);
            linhas.add(new RitmoVisto.VariabilidadeCategoria(codigo, url(codigo), nome(categoria, contas), dinheiro.apply(v.menor()),
                    dinheiro.apply(v.mediana()), dinheiro.apply(v.maior()), texto(v.dispersao()), lista.stream().map(dinheiro).toList()));
            ordem.add(v.dispersao());
        });
        List<Integer> indices = new ArrayList<>(java.util.stream.IntStream.range(0, linhas.size()).boxed().toList());
        indices.sort(java.util.Comparator.comparing((Integer i) -> ordem.get(i), java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(i -> linhas.get(i).nome() == null ? "" : linhas.get(i).nome()));
        return new RitmoVisto.Variabilidade(meses.stream().map(YearMonth::toString).toList(), indices.stream().map(linhas::get).toList(),
                NOTA_VARIABILIDADE);
    }

    /** A poupança declarada contra a observada, só com intenção declarada; o mesmo número de {@code poupanca_abaixo}. */
    private RitmoVisto.Poupanca poupanca(UUID dono, Currency moeda, ParametrosRitmo p, LocalDate marco, List<RitmoVisto.Ressalva> ressalvas,
            Function<Long, String> dinheiro) {
        if (p.poupancaDeclarada() == null) {
            return null;
        }
        BigDecimal declarada = new BigDecimal(p.poupancaDeclarada());
        List<YearMonth> meses = CalculoSituacao.mesesFechados(LocalDate.parse(p.hoje()));
        List<String> textos = meses.stream().map(YearMonth::toString).toList();
        if (marco != null && marco.isAfter(meses.get(0).atDay(1))) {
            ressalvas.add(new RitmoVisto.Ressalva("poupanca_nao_avaliada", "A poupança observada não foi avaliada: os meses fechados ("
                    + meses.get(0) + " a " + meses.get(meses.size() - 1) + ") têm histórico parcial.", null, null));
            return new RitmoVisto.Poupanca(declarada.toPlainString(), null, null, null, textos, false, p.operacaoPoupanca(), NOTA_POUPANCA);
        }
        CalculoPerfil.Poupanca o = perfil.rendaESaidas(dono, moeda, meses.get(0).atDay(1), meses.get(meses.size() - 1).atEndOfMonth());
        if (o.percentual() == null) {
            ressalvas.add(new RitmoVisto.Ressalva("poupanca_sem_renda", "A poupança observada não foi avaliada: não houve renda de "
                    + meses.get(0) + " a " + meses.get(meses.size() - 1) + ".", null, null));
        }
        return new RitmoVisto.Poupanca(declarada.toPlainString(), texto(o.percentual()), dinheiro.apply(o.renda()), dinheiro.apply(o.saidas()),
                textos, CalculoPerfil.poupancaAbaixo(o, declarada), p.operacaoPoupanca(), NOTA_POUPANCA);
    }

    /**
     * Os gastos de cada mês do período, do lado da categoria, nos mesmos fatos de {@code resumo_periodo}. Em caixa,
     * a parcela de compra feita antes do mês em que vence é fixa.
     */
    Map<YearMonth, List<Gasto>> gastos(UUID dono, Periodo periodo, String visao) {
        Set<UUID> pagamCompromisso = new HashSet<>();
        compromissos.registrosDoDono(dono).values().forEach(porNumero -> porNumero.values().forEach(registros -> registros.stream()
                .map(RegistroOcorrencia::lancamento).filter(l -> l != null).forEach(pagamCompromisso::add)));
        List<FatoPeriodo> competencia = lancamentos.fatosDoPeriodo(dono, periodo.de(), periodo.ate(), null);
        List<FatoPeriodo> fatos;
        Map<UUID, LocalDate> compradoEm = new HashMap<>();
        Set<UUID> doCredito = new HashSet<>();
        if (CalculoDeResumo.CAIXA.equals(visao)) {
            competencia.forEach(f -> compradoEm.merge(f.lancamento(), f.data(), (a, b) -> a.isBefore(b) ? a : b));
            fatos = new ArrayList<>(lancamentos.fatosForaDoCredito(dono, periodo.de(), periodo.ate(), null));
            for (CompraNoCredito compra : cartoes.comprasComVencimento(dono, periodo.de(), periodo.ate())) {
                doCredito.add(compra.lancamento());
                fatos.addAll(CalculoCaixa.fatos(compra, periodo, null));
            }
        } else {
            fatos = competencia;
        }
        Map<YearMonth, List<Gasto>> porMes = new HashMap<>();
        for (FatoPeriodo f : fatos) {
            if (f.contaComSaldo() || f.entrada()) {
                continue;
            }
            YearMonth mes = YearMonth.from(f.data());
            // Sem data de compra no período, a compra é anterior a ele.
            boolean parcelaAntiga = doCredito.contains(f.lancamento())
                    && Optional.ofNullable(compradoEm.get(f.lancamento())).map(d -> d.isBefore(mes.atDay(1))).orElse(true);
            boolean fixo = pagamCompromisso.contains(f.lancamento()) || parcelaAntiga;
            String contraparte = Argumentos.vazio(f.contraparte()) ? null : f.contraparte().strip();
            porMes.computeIfAbsent(mes, k -> new ArrayList<>()).add(new Gasto(f.lancamento(), f.codigo(), f.data(), f.conta(),
                    contraparte == null ? null : Referencias.normalizar(contraparte), contraparte, f.descricao(), f.absoluto().centavos(), fixo));
        }
        return porMes;
    }

    /** A tendência dos 3 meses fechados, como em {@code categoria_subindo}; com histórico parcial, não é avaliada. */
    private RitmoVisto.Tendencia tendencia(UUID dono, YearMonth mes, LocalDate marco, Map<UUID, Conta> contas, Map<UUID, String> codigos,
            Currency moeda, List<RitmoVisto.Ressalva> ressalvas) {
        List<YearMonth> meses = CalculoSituacao.mesesFechados(mes.atDay(1));
        if (marco != null && marco.isAfter(meses.get(0).atDay(1))) {
            ressalvas.add(new RitmoVisto.Ressalva("tendencia_nao_avaliada", "A tendência por categoria não foi avaliada: os meses fechados ("
                    + meses.get(0) + " a " + meses.get(meses.size() - 1) + ") têm histórico parcial.", null, null));
            return null;
        }
        Map<UUID, List<Long>> porCategoria = new LinkedHashMap<>();
        for (int i = 0; i < meses.size(); i++) {
            YearMonth m = meses.get(i);
            List<FatoPeriodo> fatos = lancamentos.fatosDoPeriodo(dono, m.atDay(1), m.atEndOfMonth(), null);
            for (CalculoResumo.LinhaQuebra linha : CalculoResumo.quebrar(fatos, Quebra.CATEGORIA,
                    f -> new Grupo(f.conta().toString(), f.conta().toString(), ""), moeda)) {
                UUID conta = UUID.fromString(linha.id());
                Conta c = contas.get(conta);
                if (c == null || c.tipo() != TipoConta.CATEGORIA || c.arquivada() || linha.saidas().ehZero()) {
                    continue;
                }
                porCategoria.computeIfAbsent(conta, k -> new ArrayList<>(List.of(0L, 0L, 0L))).set(i, linha.saidas().centavos());
            }
        }
        List<RitmoVisto.TendenciaCategoria> linhas = CalculoRitmo.tendencia(porCategoria).stream()
                .map(t -> new RitmoVisto.TendenciaCategoria(codigos.get(t.categoria()), url(codigos.get(t.categoria())),
                        nome(t.categoria(), contas), t.meses().stream().map(c -> Dinheiro.deCentavos(c, moeda).texto()).toList(),
                        t.variacoes().stream().map(CalculoDeRitmo::texto).toList(), t.subindo()))
                .toList();
        return new RitmoVisto.Tendencia(meses.stream().map(YearMonth::toString).toList(), linhas, NOTA_TENDENCIA);
    }

    private RitmoVisto.Contribuicao categoria(CalculoRitmo.Contribuicao c, Map<UUID, Conta> contas, Map<UUID, String> codigos,
            Function<Long, String> dinheiro) {
        UUID id = UUID.fromString(c.chave());
        String codigo = codigos.get(id);
        return new RitmoVisto.Contribuicao(codigo, url(codigo), nome(id, contas), dinheiro.apply(c.atual()), dinheiro.apply(c.tipico()),
                dinheiro.apply(c.contribuicao()));
    }

    private String url(String codigo) {
        return codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
    }

    private static String nome(UUID conta, Map<UUID, Conta> contas) {
        Conta c = contas.get(conta);
        return c == null ? null : c.nome();
    }

    private static String texto(BigDecimal percentual) {
        return percentual == null ? null : percentual.toPlainString();
    }
}
