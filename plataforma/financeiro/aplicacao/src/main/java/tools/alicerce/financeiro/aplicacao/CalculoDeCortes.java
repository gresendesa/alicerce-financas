package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
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
import tools.alicerce.financeiro.dominio.CalculoAgenda;
import tools.alicerce.financeiro.dominio.CalculoCortes;
import tools.alicerce.financeiro.dominio.CalculoCortes.LinhaCategoria;
import tools.alicerce.financeiro.dominio.CalculoRitmo;
import tools.alicerce.financeiro.dominio.CalculoRitmo.Gasto;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Monta {@code cortes_necessarios} a partir de parâmetros já resolvidos: busca o saldo de partida, os fluxos
 * conhecidos e os gastos, e deixa todas as contas para {@link CalculoCortes}. Só lê.
 */
@Component
class CalculoDeCortes {

    /** As contas que formam o saldo de partida (decisão 2). */
    static final Set<TipoConta> LIQUIDAS = Set.of(TipoConta.CORRENTE, TipoConta.CARTEIRA, TipoConta.POUPANCA);

    static final String NOTA_CARTAO = "Conservador de propósito: o gasto variável restante conta inteiro neste período, mesmo a parte que "
            + "for no cartão e só vencer na fatura seguinte. Com cartão, o déficit real tende a ser menor; diga isso ao dono.";
    static final String NOTA_PAPEL = "Todos os números vêm do servidor: apresente-os como vieram, sem somar nem recalcular. Para negociar "
            + "outro plano com o dono, chame de novo com outra estimativa ou outro alvo.";
    static final String NOTA_PLANO = "O plano cobre o déficit pelo espaço de corte das categorias, proporcional a ele; as essenciais só "
            + "entram depois das outras. Compromissos e faturas não são cortáveis neste mês, mas estão em nao_cortaveis para o dono decidir se "
            + "renegocia algum.";

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final Compromissos compromissos;
    private final ObterAgenda agenda;
    private final Conferencias conferencias;
    private final CalculoDeRitmo ritmo;
    private final Enderecos enderecos;
    private final Metas metas;

    CalculoDeCortes(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, Compromissos compromissos, ObterAgenda agenda,
            Conferencias conferencias, CalculoDeRitmo ritmo, Enderecos enderecos, Metas metas) {
        this.metas = metas;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.compromissos = compromissos;
        this.agenda = agenda;
        this.conferencias = conferencias;
        this.ritmo = ritmo;
        this.enderecos = enderecos;
    }

    CortesVisto calcular(UUID dono, Currency moeda, ParametrosCortes p, String operacaoDeclaracao, OffsetDateTime agora) {
        LocalDate hoje = LocalDate.parse(p.hoje());
        LocalDate fim = LocalDate.parse(p.fim());
        Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moeda).texto();
        List<Conta> todas = estrutura.contas(dono);
        Map<UUID, Conta> contas = todas.stream().collect(Collectors.toMap(Conta::id, Function.identity()));
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono);
        List<CortesVisto.Ressalva> ressalvas = new ArrayList<>();

        // Saldo de partida (decisão 2).
        Partida saldoPartida = partida(dono, moeda, hoje, todas, codigos, ressalvas);
        long partida = saldoPartida.centavos();
        List<CortesVisto.ContaPartida> contasPartida = saldoPartida.contas();

        // Fluxos conhecidos (decisão 3): ocorrências em aberto e o restante das faturas, atrasados inclusive.
        List<ItemAgenda> saidas = new ArrayList<>();
        List<ItemAgenda> entradas = new ArrayList<>();
        List<CalculoAgenda.Item> itens = new ArrayList<>();
        List<String> compromissosOutraMoeda = new ArrayList<>();
        for (Compromissos.Aberta a : compromissos.abertas(dono, hoje, fim)) {
            if (!a.compromisso().moeda().equals(moeda.getCurrencyCode())) {
                compromissosOutraMoeda.add(a.compromisso().nome());
                continue;
            }
            (a.saida() ? saidas : entradas).add(compromissos.item(dono, a, hoje));
            itens.add(new CalculoAgenda.Item(a.saida(), a.ocorrencia().valorRestanteCentavos(), a.ocorrencia().vencimento().isBefore(hoje)));
        }
        if (!compromissosOutraMoeda.isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("compromisso_outra_moeda", "Fora dos fluxos, por estarem em outra moeda: "
                    + String.join(", ", new java.util.LinkedHashSet<>(compromissosOutraMoeda)) + "."));
        }
        for (ObterAgenda.FaturaEmAberto f : agenda.faturasEmAberto(dono, moeda, hoje)) {
            if (!f.venceEm().isAfter(fim)) {
                saidas.add(f.item());
                itens.add(new CalculoAgenda.Item(true, f.restanteCentavos(), f.atrasada()));
            }
        }
        java.util.Comparator<ItemAgenda> ordem = java.util.Comparator.comparing(ItemAgenda::vencimento).thenComparing(ItemAgenda::nome);
        saidas.sort(ordem);
        entradas.sort(ordem);
        CalculoAgenda.Totais fluxos = CalculoAgenda.totais(itens);

        // Gasto variável (decisão 4): a base de ritmo_gastos, em competência.
        YearMonth mes = YearMonth.from(hoje);
        BaseVariavel base = baseVariavel(dono, contas, mes);
        List<YearMonth> validos = base.validos();
        Map<YearMonth, List<Gasto>> porMes = base.porMes();
        Map<YearMonth, List<Gasto>> referencia = base.referencia();
        Set<UUID> essenciais = comSubcategorias(p.essenciais().stream().map(UUID::fromString).collect(Collectors.toSet()), todas);
        List<CalculoCortes.Categoria> categorias = CalculoCortes.categorias(hoje, fim, porMes.getOrDefault(mes, List.of()), referencia,
                essenciais);

        long alvo = Dinheiro.ler(p.valorAlvo(), moeda, "alvo").centavos();
        CalculoCortes.Estimativa estimativa = CalculoCortes.Estimativa.deCodigo(p.estimativa()).orElseThrow();
        CalculoCortes.Resultado r = CalculoCortes.calcular(new CalculoCortes.Entrada(partida, fluxos.aReceber(), fluxos.aPagar(), categorias,
                validos.size(), alvo, estimativa));

        confianca(dono, hoje, validos.size(), base, contas, ressalvas, "um plano", " O gasto variável restante é uma estimativa fraca (zero "
                + "sem nenhum mês), e não há plano de cortes: repasse ao dono o saldo projetado e a distância até o alvo, com esta ressalva, e "
                + "não sugira cortes por conta própria.");

        List<CortesVisto.CategoriaCorte> linhas = r.categorias().stream().map(l -> {
            String codigo = codigos.get(l.id());
            return new CortesVisto.CategoriaCorte(codigo, url(codigo), nome(l.id(), contas), l.essencial(), dinheiro.apply(l.gastoAteHoje()),
                    dinheiro.apply(l.tipico()), dinheiro.apply(l.restante()), dinheiro.apply(l.piso()), dinheiro.apply(l.espaco()),
                    dinheiro.apply(l.corte()));
        }).toList();
        CortesVisto.Plano plano = null;
        if (r.historicoSuficiente()) {
            List<CortesVisto.Corte> cortes = r.plano().stream().map(l -> corte(l, contas, codigos, dinheiro)).toList();
            plano = new CortesVisto.Plano(cortes, dinheiro.apply(r.coberto()), dinheiro.apply(r.descoberto()), dinheiro.apply(r.espacoTotal()),
                    r.precisaEssenciais(), NOTA_PLANO);
        }
        List<String> notas = new ArrayList<>();
        notas.add(NOTA_CARTAO);
        notas.add(NOTA_PAPEL);
        return new CortesVisto(agora.toString(), p.hoje(), p.fim(), moeda.getCurrencyCode(), p.estimativa(),
                new CortesVisto.Alvo(p.alvo(), dinheiro.apply(alvo), p.fim(), operacaoDeclaracao, p.meta() == null ? null
                        : new CortesVisto.MetaAlvo(p.meta(), url(p.meta()), p.nomeMeta(), p.aporteMeta(), p.piso())),
                dinheiro.apply(r.saldoPartida()), contasPartida,
                dinheiro.apply(r.entradas()), dinheiro.apply(r.saidas()), dinheiro.apply(r.variavelRestante()), dinheiro.apply(r.saldoProjetado()),
                dinheiro.apply(r.distancia()), dinheiro.apply(Math.max(0, r.distancia())), dinheiro.apply(r.deficit()), saidas, entradas, linhas,
                plano, validos.size(), CalculoRitmo.MESES_PADRAO, ressalvas, notas);
    }

    /**
     * O saldo de partida: as contas líquidas na moeda do dono, hoje, com cada conta; as de outra moeda saem como ressalva. A
     * conta reservada a uma meta em andamento fica fora, como manda a modelagem.
     */
    record Partida(long centavos, List<CortesVisto.ContaPartida> contas) {}

    Partida partida(UUID dono, Currency moeda, LocalDate hoje, List<Conta> todas, Map<UUID, String> codigos, List<CortesVisto.Ressalva> ressalvas) {
        Map<UUID, Long> saldos = lancamentos.saldosCentavosAte(dono, hoje);
        Set<UUID> reservadas = metas.contasReservadas(dono);
        long partida = 0;
        List<CortesVisto.ContaPartida> contasPartida = new ArrayList<>();
        List<String> outraMoeda = new ArrayList<>();
        List<String> deMetas = new ArrayList<>();
        for (Conta c : todas) {
            if (c.arquivada() || !LIQUIDAS.contains(c.tipo())) {
                continue;
            }
            if (reservadas.contains(c.id())) {
                deMetas.add(c.nome());
                continue;
            }
            if (!c.moeda().equals(moeda.getCurrencyCode())) {
                outraMoeda.add(c.nome() + " (" + c.moeda() + ")");
                continue;
            }
            long saldo = saldos.getOrDefault(c.id(), 0L);
            partida = Math.addExact(partida, saldo);
            String codigo = codigos.get(c.id());
            contasPartida.add(new CortesVisto.ContaPartida(codigo, url(codigo), c.nome(), c.tipo().codigo(),
                    Dinheiro.deCentavos(saldo, moeda).texto()));
        }
        if (!outraMoeda.isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("conta_outra_moeda", "Fora do saldo de partida, por estarem em outra moeda: "
                    + String.join(", ", outraMoeda) + "."));
        }
        if (!deMetas.isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("conta_reservada_meta", "Fora do saldo de partida, por estarem reservadas a metas: "
                    + String.join(", ", deMetas) + "."));
        }
        return new Partida(partida, contasPartida);
    }

    /**
     * A base do gasto variável, a de {@code ritmo_gastos} em competência: os meses de referência válidos (os 6 antes de
     * {@code mes}, só os que começam depois do marco mais recente) e os gastos de cada um, mais os de {@code mes}.
     *
     * @param marco a conta com o marco mais recente e a data dele, se há
     */
    record BaseVariavel(List<YearMonth> validos, Optional<Map.Entry<UUID, LocalDate>> marco, Map<YearMonth, List<Gasto>> porMes,
            Map<YearMonth, List<Gasto>> referencia, Periodo periodo) {

        LocalDate marcoMaisRecente() {
            return marco.map(Map.Entry::getValue).orElse(null);
        }
    }

    BaseVariavel baseVariavel(UUID dono, Map<UUID, Conta> contas, YearMonth mes) {
        Optional<Map.Entry<UUID, LocalDate>> marco = estrutura.marcos(dono).entrySet().stream()
                .filter(m -> contas.containsKey(m.getKey()) && !contas.get(m.getKey()).arquivada())
                .max(Map.Entry.comparingByValue());
        LocalDate marcoMaisRecente = marco.map(Map.Entry::getValue).orElse(null);
        List<YearMonth> validos = CalculoRitmo.mesesValidos(mes, CalculoRitmo.MESES_PADRAO, marcoMaisRecente);
        YearMonth primeiro = validos.isEmpty() ? mes : validos.get(0);
        Periodo periodo = new Periodo(primeiro.atDay(1), mes.atEndOfMonth());
        Map<YearMonth, List<Gasto>> porMes = ritmo.gastos(dono, periodo, CalculoDeResumo.COMPETENCIA);
        Map<YearMonth, List<Gasto>> referencia = new LinkedHashMap<>();
        validos.forEach(m -> referencia.put(m, porMes.getOrDefault(m, List.of())));
        return new BaseVariavel(validos, marco, porMes, referencia, periodo);
    }

    /**
     * As ressalvas de confiança: histórico curto, conferência vencida e
     * diferença pendente nas contas líquidas.
     *
     * @param para o que os meses mínimos permitem, como "um plano"
     * @param consequencia o que o histórico curto muda na resposta e o que o assistente não deve fazer
     */
    void confianca(UUID dono, LocalDate hoje, int meses, BaseVariavel base, Map<UUID, Conta> contas, List<CortesVisto.Ressalva> ressalvas,
            String para, String consequencia) {
        if (meses < CalculoCortes.MESES_MINIMOS) {
            LocalDate marcoMaisRecente = base.marcoMaisRecente();
            String desde = marcoMaisRecente == null ? "" : " O histórico está completo desde " + CalculoRitmo.completoDesde(marcoMaisRecente)
                    + ", porque a conta " + contas.get(base.marco().get().getKey()).nome() + " só está coberta desde " + marcoMaisRecente + ".";
            ressalvas.add(new CortesVisto.Ressalva("historico_insuficiente", "Só " + meses + " dos " + CalculoRitmo.MESES_PADRAO
                    + " meses de referência têm histórico completo, e são precisos " + CalculoCortes.MESES_MINIMOS + " para " + para + "." + desde
                    + consequencia));
        }
        List<String> vencidas = new ArrayList<>();
        List<String> pendentes = new ArrayList<>();
        conferencias.porConta(dono, hoje).forEach((conta, v) -> {
            Conta c = contas.get(conta);
            if (c == null || c.arquivada() || !LIQUIDAS.contains(c.tipo())) {
                return;
            }
            if (v.vencida()) {
                vencidas.add(c.nome());
            }
            if ("pendente".equals(v.situacao())) {
                pendentes.add(c.nome() + " (diferença de " + ResumoOperacao.dinheiro(v.diferencaPendente(), v.moeda()) + ")");
            }
        });
        if (!vencidas.isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("conferencia_vencida", "Sem conferência há mais de " + tools.alicerce.financeiro.dominio
                    .PrazoConferencia.DIAS + " dias: " + String.join(", ", vencidas) + ". O saldo de partida pode estar errado; sugira "
                    + "conferir_saldo."));
        }
        if (!pendentes.isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("diferenca_pendente", "Conferência com diferença não resolvida: " + String.join(", ", pendentes)
                    + ". O saldo de partida pode estar errado."));
        }
    }

    /** As essenciais e as subcategorias delas, em qualquer profundidade. */
    static Set<UUID> comSubcategorias(Set<UUID> essenciais, List<Conta> contas) {
        Set<UUID> todas = new HashSet<>(essenciais);
        boolean cresceu = true;
        while (cresceu) {
            cresceu = false;
            for (Conta c : contas) {
                if (c.contaPai() != null && todas.contains(c.contaPai()) && todas.add(c.id())) {
                    cresceu = true;
                }
            }
        }
        return todas;
    }

    private CortesVisto.Corte corte(LinhaCategoria l, Map<UUID, Conta> contas, Map<UUID, String> codigos, Function<Long, String> dinheiro) {
        String codigo = codigos.get(l.id());
        return new CortesVisto.Corte(codigo, url(codigo), nome(l.id(), contas), l.essencial(), dinheiro.apply(l.corte()),
                dinheiro.apply(l.restante()), dinheiro.apply(l.restanteComCorte()));
    }

    private String url(String codigo) {
        return codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
    }

    private static String nome(UUID conta, Map<UUID, Conta> contas) {
        Conta c = contas.get(conta);
        return c == null ? null : c.nome();
    }
}
