package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.Situacao.MesVisto;
import tools.alicerce.financeiro.aplicacao.Situacao.SinalVisto;
import tools.alicerce.financeiro.dominio.CalculoPerfil;
import tools.alicerce.financeiro.dominio.CalculoProjecao;
import tools.alicerce.financeiro.dominio.CalculoResumo;
import tools.alicerce.financeiro.dominio.CalculoRitmo;
import tools.alicerce.financeiro.dominio.CalculoResumo.Grupo;
import tools.alicerce.financeiro.dominio.CalculoSituacao;
import tools.alicerce.financeiro.dominio.CalculoSituacao.Codigo;
import tools.alicerce.financeiro.dominio.CalculoSituacao.Severidade;
import tools.alicerce.financeiro.dominio.CalculoSituacao.Tendencia;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.FatoPeriodo;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.Quebra;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorDeclaracao;

/**
 * Capacidade {@code situacao}: o diagnóstico para tarefas agendadas. Só sinais objetivos, sem opinião, cada
 * um pela regra fixa de {@link CalculoSituacao}. Os números vêm das mesmas leituras de {@code obter_agenda},
 * {@code listar_compromissos} e {@code resumo_periodo}: nada é recalculado por fora. Só lê.
 */
@Component
public class ObterSituacao {

    private static final String NENHUM = "Nenhum sinal: nada atrasado, nenhuma fatura vencendo nos próximos "
            + CalculoSituacao.DIAS_FATURA + " dias, nenhuma conferência pendente ou antiga, nenhuma categoria ou parcela subindo e nenhuma "
            + "regra do perfil quebrada.";
    private static final String NOTA = "Sinais objetivos, por regra fixa: crítico é ocorrência atrasada há mais de "
            + CalculoSituacao.DIAS_ATRASO_CRITICO + " dias, fatura que vence hoje, amanhã ou já venceu, saldo projetado abaixo da reserva "
            + "mínima no mês corrente ou no seguinte e meta protegida atrasada; aviso, os demais atrasos, faturas, reservas e metas, toda "
            + "conferência pendente, parcelas acima do teto e poupança abaixo da declarada; info, conferência antiga, categoria subindo e "
            + "parcelas subindo. Os sinais do perfil dependem do que o dono declarou: sem a declaração, saem em nao_avaliados, e você "
            + "pergunta ao dono, sem deduzir a regra. Os valores já vêm calculados: repasse-os como vieram, nunca some.";

    private final RepositorioDono donos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioLancamentos lancamentos;
    private final Compromissos compromissos;
    private final Conferencias conferencias;
    private final ObterAgenda agenda;
    private final Enderecos enderecos;
    private final Declaracoes declaracoes;
    private final GastoTipico gastoTipico;
    private final CalculoDeProjecao projecao;
    private final LeituraDoPerfil perfil;
    private final Metas metas;

    ObterSituacao(RepositorioDono donos, RepositorioEstrutura estrutura, RepositorioLancamentos lancamentos, Compromissos compromissos,
            Conferencias conferencias, ObterAgenda agenda, Enderecos enderecos, Declaracoes declaracoes, GastoTipico gastoTipico,
            CalculoDeProjecao projecao, LeituraDoPerfil perfil, Metas metas) {
        this.declaracoes = declaracoes;
        this.gastoTipico = gastoTipico;
        this.projecao = projecao;
        this.perfil = perfil;
        this.metas = metas;
        this.donos = donos;
        this.estrutura = estrutura;
        this.lancamentos = lancamentos;
        this.compromissos = compromissos;
        this.conferencias = conferencias;
        this.agenda = agenda;
        this.enderecos = enderecos;
    }

    private record Sinal(Severidade severidade, Codigo codigo, String ordem, SinalVisto visto) {}

    public Situacao executar(Chamador chamador) {
        return executar(chamador, null);
    }

    /** @param horizonteMeses de 1 a 24, o mês de hoje inclusive; padrão 3 */
    public Situacao executar(Chamador chamador, Integer horizonteMeses) {
        int horizonte = horizonteMeses == null ? CalculoPerfil.HORIZONTE_SITUACAO : horizonteMeses;
        if (horizonte < 1 || horizonte > CalculoProjecao.HORIZONTE_MAXIMO) {
            throw ErroTipado.validacao("horizonte_meses", "O horizonte vai de 1 a " + CalculoProjecao.HORIZONTE_MAXIMO
                    + " meses, contando o mês de hoje; o padrão é " + CalculoPerfil.HORIZONTE_SITUACAO + ".");
        }
        UUID dono = chamador.dono();
        Currency moeda = donos.buscar(dono).orElseThrow().moeda();
        LocalDate hoje = compromissos.hoje(dono);
        List<Sinal> sinais = new ArrayList<>();
        List<String> ressalvas = new ArrayList<>();
        List<Situacao.NaoAvaliado> naoAvaliados = new ArrayList<>();
        compromissos(dono, hoje, sinais);
        faturas(dono, moeda, hoje, sinais);
        conferencias(dono, hoje, sinais);
        categorias(dono, moeda, hoje, sinais, ressalvas);
        perfil(dono, moeda, hoje, horizonte, sinais, ressalvas, naoAvaliados);
        sinais.sort(Comparator.comparing(Sinal::severidade).thenComparing(Sinal::codigo).thenComparing(Sinal::ordem));
        List<SinalVisto> vistos = sinais.stream().map(Sinal::visto).toList();
        Situacao.Contagem contagem = new Situacao.Contagem(contar(sinais, Severidade.CRITICO), contar(sinais, Severidade.AVISO),
                contar(sinais, Severidade.INFO));
        return new Situacao(hoje.toString(), moeda.getCurrencyCode(), vistos, contagem, vistos.isEmpty() ? NENHUM : null,
                ressalvas.isEmpty() ? null : ressalvas, NOTA, horizonte, naoAvaliados.isEmpty() ? null : naoAvaliados);
    }

    // ---- sinais do perfil ----

    private void perfil(UUID dono, Currency moeda, LocalDate hoje, int horizonte, List<Sinal> sinais, List<String> ressalvas,
            List<Situacao.NaoAvaliado> naoAvaliados) {
        Map<TipoDeclaracao, Declaracoes.Vigente> vigentes = declaracoes.vigentes(dono);
        Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moeda).texto();
        ParametrosProjecao.Regras regras = Projetar.regras(declaracoes, vigentes, () -> gastoTipico.calcular(dono, hoje), moeda);
        projetados(dono, moeda, hoje, horizonte, vigentes, regras, dinheiro, sinais, ressalvas, naoAvaliados);
        Optional<String> parcial = historicoParcial(dono, CalculoSituacao.mesesFechados(hoje));
        parcelasSubindo(dono, moeda, hoje, parcial, dinheiro, sinais, ressalvas);
        poupanca(dono, moeda, hoje, vigentes, parcial, dinheiro, sinais, ressalvas, naoAvaliados);
        metasAtrasadas(dono, hoje, vigentes, parcial, sinais, ressalvas);
    }

    /**
     * {@code reserva_abaixo_projetada} e {@code parcelas_acima_teto}, pela linha de base de {@code projetar} no horizonte
     * (decisão 3): o mesmo cálculo, sem nada recalculado aqui.
     */
    private void projetados(UUID dono, Currency moeda, LocalDate hoje, int horizonte, Map<TipoDeclaracao, Declaracoes.Vigente> vigentes,
            ParametrosProjecao.Regras regras, Function<Long, String> dinheiro, List<Sinal> sinais, List<String> ressalvas,
            List<Situacao.NaoAvaliado> naoAvaliados) {
        boolean temReserva = vigentes.containsKey(TipoDeclaracao.RESERVA_MINIMA);
        boolean temTeto = vigentes.containsKey(TipoDeclaracao.TETO_PARCELAS);
        if (!temReserva) {
            naoAvaliados.add(new Situacao.NaoAvaliado(Codigo.RESERVA_ABAIXO_PROJETADA.codigo(), TipoDeclaracao.RESERVA_MINIMA.codigo(),
                    "Sem reserva mínima declarada. Pergunte ao dono quanto ele quer ter sempre guardado (um valor ou meses de gasto)."));
        } else if (regras.reservaSemValor()) {
            ressalvas.add(Codigo.RESERVA_ABAIXO_PROJETADA.codigo() + " não foi avaliado: a reserva mínima é de " + regras.reservaMesesDeGasto()
                    + " meses de gasto, e o gasto mensal típico precisa de " + CalculoRitmo.MESES_MINIMOS + " meses de referência com "
                    + "histórico completo. Não estime a reserva por conta própria.");
        }
        if (!temTeto) {
            naoAvaliados.add(new Situacao.NaoAvaliado(Codigo.PARCELAS_ACIMA_TETO.codigo(), TipoDeclaracao.TETO_PARCELAS.codigo(),
                    "Sem teto de parcelas declarado. Pergunte ao dono qual é o máximo da renda de um mês que as parcelas podem levar."));
        }
        if (regras.reserva() == null && regras.teto() == null) {
            return;
        }
        Declaracoes.Vigente piso = vigentes.get(TipoDeclaracao.PISO_PERIODO);
        String valorPiso = piso == null ? null : dinheiro.apply(((ValorDeclaracao.PisoPeriodo) piso.valor()).saldoMinimoCentavos());
        ParametrosProjecao p = new ParametrosProjecao(hoje.toString(), horizonte, valorPiso, piso == null ? null : piso.declaracao().operacao(),
                List.of(), regras);
        CalculoDeProjecao.LinhaDeBase linha = projecao.linhaDeBase(dono, moeda, p);
        CalculoProjecao.Resultado r = linha.resultado();
        if (!linha.preparo().b().historicoSuficiente()) {
            ressalvas.add("Sem estimativa de gasto variável: só " + linha.preparo().b().mesesReferencia() + " meses de referência têm histórico "
                    + "completo, e são precisos " + CalculoRitmo.MESES_MINIMOS + ". reserva_abaixo_projetada e parcelas_acima_teto usam saldos "
                    + "sem o gasto do dia a dia, otimistas demais. Não estime esse gasto por conta própria.");
        }
        Map<YearMonth, CalculoProjecao.Mes> porMes = new HashMap<>();
        r.meses().forEach(m -> porMes.put(m.mes(), m));
        if (regras.reserva() != null && !r.abaixoReservaP80().isEmpty()) {
            YearMonth primeiro = r.abaixoReservaP80().get(0);
            Severidade s = CalculoPerfil.severidadeReserva(r.meses().indexOf(porMes.get(primeiro)));
            String operacao = regras.operacaoReserva();
            sinais.add(new Sinal(s, Codigo.RESERVA_ABAIXO_PROJETADA, primeiro.toString(), new SinalVisto(Codigo.RESERVA_ABAIXO_PROJETADA.codigo(),
                    s.codigo(), "declaracao", operacao, urlOperacao(operacao), "Reserva mínima", null, null, null, moeda.getCurrencyCode(), null,
                    null, null, null, null, null, null, null, null, null, new Situacao.DetalhePerfil(primeiro.toString(),
                            texto(r.abaixoReservaP80()), dinheiro.apply(porMes.get(primeiro).finalP80()), regras.reserva(),
                            regras.reservaMesesDeGasto(), regras.gastoMensalTipico(), null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, operacao))));
        }
        if (regras.teto() != null) {
            BigDecimal teto = new BigDecimal(regras.teto());
            List<CalculoProjecao.Mes> acima = r.meses().stream().filter(m -> CalculoPerfil.acimaDoTeto(m.comprometimento(), teto)).toList();
            if (!acima.isEmpty()) {
                CalculoProjecao.Mes m = acima.get(0);
                Severidade s = Severidade.AVISO;
                String operacao = regras.operacaoTeto();
                sinais.add(new Sinal(s, Codigo.PARCELAS_ACIMA_TETO, m.mes().toString(), new SinalVisto(Codigo.PARCELAS_ACIMA_TETO.codigo(),
                        s.codigo(), "declaracao", operacao, urlOperacao(operacao), "Teto de parcelas", null, null, null, moeda.getCurrencyCode(),
                        null, null, null, null, null, null, null, null, null, null, new Situacao.DetalhePerfil(m.mes().toString(),
                                acima.stream().map(x -> x.mes().toString()).toList(), null, null, null, null, dinheiro.apply(m.parcelas()),
                                dinheiro.apply(m.renda()), m.comprometimento().toPlainString(), teto.toPlainString(), null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null, operacao))));
            }
            if (!r.mesesSemRenda().isEmpty()) {
                ressalvas.add(Codigo.PARCELAS_ACIMA_TETO.codigo() + " não foi avaliado em " + String.join(", ", texto(r.mesesSemRenda()))
                        + ": não há renda prevista nesses meses. A renda da projeção vem dos compromissos de entrada.");
            }
        }
    }

    /** {@code parcelas_subindo}: as parcelas dos 3 meses fechados e do corrente; com histórico parcial, não é avaliado. */
    private void parcelasSubindo(UUID dono, Currency moeda, LocalDate hoje, Optional<String> parcial, Function<Long, String> dinheiro,
            List<Sinal> sinais, List<String> ressalvas) {
        if (parcial.isPresent()) {
            ressalvas.add(Codigo.PARCELAS_SUBINDO.codigo() + " não foi avaliado: " + parcial.get());
            return;
        }
        YearMonth atual = YearMonth.from(hoje);
        Map<YearMonth, Long> porMes = perfil.parcelasPorMes(dono, moeda, atual.minusMonths(CalculoPerfil.ALTAS_PARCELAS), atual, hoje);
        List<Long> valores = List.copyOf(porMes.values());
        if (!CalculoPerfil.parcelasSubindo(valores)) {
            return;
        }
        Severidade s = Severidade.INFO;
        List<Situacao.MesParcelas> meses = porMes.entrySet().stream().map(e -> new Situacao.MesParcelas(e.getKey().toString(),
                dinheiro.apply(e.getValue()), e.getKey().equals(atual))).toList();
        sinais.add(new Sinal(s, Codigo.PARCELAS_SUBINDO, atual.toString(), new SinalVisto(Codigo.PARCELAS_SUBINDO.codigo(), s.codigo(), "parcelas",
                null, null, "Parcelas", null, null, null, moeda.getCurrencyCode(), null, null, null, null, null, null, null, null, null, null,
                new Situacao.DetalhePerfil(null, null, null, null, null, null, dinheiro.apply(valores.get(valores.size() - 1)), null, null, null,
                        meses, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null))));
    }

    /** {@code poupanca_abaixo}: a poupança dos 3 meses fechados, em competência, contra a declarada; "não poupar" nunca dispara. */
    private void poupanca(UUID dono, Currency moeda, LocalDate hoje, Map<TipoDeclaracao, Declaracoes.Vigente> vigentes, Optional<String> parcial,
            Function<Long, String> dinheiro, List<Sinal> sinais, List<String> ressalvas, List<Situacao.NaoAvaliado> naoAvaliados) {
        Declaracoes.Vigente intencao = vigentes.get(TipoDeclaracao.INTENCAO_POUPANCA);
        if (intencao == null) {
            naoAvaliados.add(new Situacao.NaoAvaliado(Codigo.POUPANCA_ABAIXO.codigo(), TipoDeclaracao.INTENCAO_POUPANCA.codigo(),
                    "Sem intenção de poupança declarada. Pergunte ao dono quanto da renda ele quer poupar."));
            return;
        }
        BigDecimal declarada = ((ValorDeclaracao.IntencaoPoupanca) intencao.valor()).percentual();
        if (declarada.signum() == 0) {
            return;
        }
        if (parcial.isPresent()) {
            ressalvas.add(Codigo.POUPANCA_ABAIXO.codigo() + " não foi avaliado: " + parcial.get());
            return;
        }
        List<YearMonth> meses = CalculoSituacao.mesesFechados(hoje);
        CalculoPerfil.Poupanca observada = perfil.rendaESaidas(dono, moeda, meses.get(0).atDay(1), meses.get(meses.size() - 1).atEndOfMonth());
        if (observada.percentual() == null) {
            ressalvas.add(Codigo.POUPANCA_ABAIXO.codigo() + " não foi avaliado: não houve renda nos meses " + meses.get(0) + " a "
                    + meses.get(meses.size() - 1) + ".");
            return;
        }
        if (!CalculoPerfil.poupancaAbaixo(observada, declarada)) {
            return;
        }
        Severidade s = Severidade.AVISO;
        String operacao = intencao.declaracao().operacao();
        sinais.add(new Sinal(s, Codigo.POUPANCA_ABAIXO, "", new SinalVisto(Codigo.POUPANCA_ABAIXO.codigo(), s.codigo(), "declaracao", operacao,
                urlOperacao(operacao), "Intenção de poupança", null, null, null, moeda.getCurrencyCode(), null, null, null, null, null, null, null,
                null, null, null, new Situacao.DetalhePerfil(null, null, null, null, null, null, null, null, null, null, null,
                        observada.percentual().toPlainString(), declarada.toPlainString(), dinheiro.apply(observada.renda()),
                        dinheiro.apply(observada.saidas()), texto(meses), null, null, null, null, null, null, null, null, null, null, operacao))));
    }

    /** {@code meta_atrasada}: cada meta ativa com data alvo, pelo ritmo dos aportes líquidos dos 3 meses fechados; crítica se protegida. */
    private void metasAtrasadas(UUID dono, LocalDate hoje, Map<TipoDeclaracao, Declaracoes.Vigente> vigentes, Optional<String> parcial,
            List<Sinal> sinais, List<String> ressalvas) {
        List<String> semRitmo = new ArrayList<>();
        Declaracoes.Vigente declarada = vigentes.get(TipoDeclaracao.META_PROTEGIDA);
        Set<UUID> protegidas = declarada == null ? Set.of() : Set.copyOf(((ValorDeclaracao.MetaProtegida) declarada.valor()).metas());
        List<YearMonth> meses = CalculoSituacao.mesesFechados(hoje);
        LocalDate de = meses.get(0).atDay(1);
        LocalDate ate = meses.get(meses.size() - 1).atEndOfMonth();
        Map<UUID, Long> progressos = metas.progressos(dono);
        for (Meta m : metas.emAndamento(dono)) {
            long progresso = progressos.getOrDefault(m.id(), 0L);
            Optional<CalculoPerfil.RitmoMeta> atraso = CalculoPerfil.metaAtrasada(m, progresso, perfil.aportesLiquidos(m.id(), de, ate), hoje);
            if (atraso.isEmpty()) {
                continue;
            }
            CalculoPerfil.RitmoMeta a = atraso.get();
            // Com histórico parcial, o ritmo dos aportes é desconhecido: só a data vencida ainda vale.
            if (parcial.isPresent() && !a.prazoVencido()) {
                semRitmo.add(m.nome());
                continue;
            }
            boolean protegida = protegidas.contains(m.id());
            Severidade s = CalculoPerfil.severidadeMeta(protegida);
            MetaVista v = metas.ver(dono, m, progresso, hoje);
            Currency moedaMeta = Currency.getInstance(m.moeda());
            Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moedaMeta).texto();
            sinais.add(new Sinal(s, Codigo.META_ATRASADA, m.dataAlvo() + m.nome(), new SinalVisto(Codigo.META_ATRASADA.codigo(), s.codigo(), "meta",
                    v.id(), v.url(), m.nome(), null, null, null, m.moeda(), null, null, null, null, null, null, null, null, null, null,
                    new Situacao.DetalhePerfil(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                            texto(meses), v.valorAlvo(), v.dataAlvo(), dinheiro.apply(a.progresso()), dinheiro.apply(a.falta()),
                            dinheiro.apply(a.aportesLiquidos()), dinheiro.apply(a.ritmoMensal()), dinheiro.apply(a.aporteNecessario()),
                            a.prazoVencido(), a.mesProjetado() == null ? null : a.mesProjetado().toString(), protegida,
                            protegida ? declarada.declaracao().operacao() : null))));
        }
        if (!semRitmo.isEmpty()) {
            ressalvas.add(Codigo.META_ATRASADA.codigo() + " não foi avaliado para " + String.join(", ", semRitmo) + ": o ritmo dos aportes "
                    + "precisa dos meses fechados inteiros, e " + parcial.get() + " Não diga que a meta está parada nem atrasada.");
        }
    }

    /** O histórico dos meses é parcial se alguma conta ativa tem marco depois do primeiro dia deles. */
    private Optional<String> historicoParcial(UUID dono, List<YearMonth> meses) {
        LocalDate inicio = meses.get(0).atDay(1);
        Map<UUID, Conta> contas = new HashMap<>();
        estrutura.contas(dono).forEach(c -> contas.put(c.id(), c));
        return estrutura.marcos(dono).entrySet().stream()
                .filter(m -> m.getValue().isAfter(inicio) && contas.containsKey(m.getKey()) && !contas.get(m.getKey()).arquivada())
                .max(Map.Entry.comparingByValue())
                .map(m -> "os meses fechados (" + meses.get(0) + " a " + meses.get(meses.size() - 1) + ") têm histórico parcial, porque a conta "
                        + contas.get(m.getKey()).nome() + " só está coberta desde " + m.getValue() + ".");
    }

    private String urlOperacao(String codigo) {
        return codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
    }

    private static List<String> texto(List<YearMonth> meses) {
        return meses.stream().map(YearMonth::toString).toList();
    }

    private static int contar(List<Sinal> sinais, Severidade severidade) {
        return (int) sinais.stream().filter(s -> s.severidade() == severidade).count();
    }

    /** O mesmo item da agenda: o valor é o que falta, inclusive na paga em parte. */
    private void compromissos(UUID dono, LocalDate hoje, List<Sinal> sinais) {
        for (Compromissos.Aberta a : compromissos.abertas(dono, hoje, hoje)) {
            Optional<Severidade> severidade = CalculoSituacao.compromissoAtrasado(a.ocorrencia(), hoje);
            if (severidade.isEmpty()) {
                continue;
            }
            ItemAgenda i = compromissos.item(dono, a, hoje);
            long atraso = CalculoSituacao.dias(a.ocorrencia().vencimento(), hoje);
            sinais.add(new Sinal(severidade.get(), Codigo.COMPROMISSO_ATRASADO, i.vencimento() + i.nome(),
                    new SinalVisto(Codigo.COMPROMISSO_ATRASADO.codigo(), severidade.get().codigo(), "compromisso", i.id(), i.url(), i.nome(),
                            i.direcao(), i.vencimento(), i.valor(), i.moeda(), i.parcela(), atraso, null, i.valorPrevisto(), i.valorPago(),
                            null, null, null, null, null, null)));
        }
    }

    /** As mesmas faturas da agenda, pelo mesmo restante. */
    private void faturas(UUID dono, Currency moeda, LocalDate hoje, List<Sinal> sinais) {
        for (ObterAgenda.FaturaEmAberto f : agenda.faturasEmAberto(dono, moeda, hoje)) {
            Optional<Severidade> severidade = CalculoSituacao.faturaVencendo(f.venceEm(), f.restanteCentavos(), hoje);
            if (severidade.isEmpty()) {
                continue;
            }
            ItemAgenda i = f.item();
            sinais.add(new Sinal(severidade.get(), Codigo.FATURA_VENCENDO, i.vencimento() + i.nome(),
                    new SinalVisto(Codigo.FATURA_VENCENDO.codigo(), severidade.get().codigo(), "fatura", i.id(), i.url(), i.nome(), i.direcao(),
                            i.vencimento(), i.valor(), i.moeda(), null, null, CalculoSituacao.dias(hoje, f.venceEm()), null, null, null, null,
                            null, null, null, null)));
        }
    }

    /** As mesmas conferências de {@code conferencias_vencidas}, pelo mesmo prazo. */
    private void conferencias(UUID dono, LocalDate hoje, List<Sinal> sinais) {
        for (Conferencias.Estado e : conferencias.estados(dono, hoje).values()) {
            ConferenciaVista v = e.vista();
            CalculoSituacao.conferenciaPendente(e.situacao()).ifPresent(s -> sinais.add(new Sinal(s, Codigo.CONFERENCIA_PENDENTE, v.nome(),
                    new SinalVisto(Codigo.CONFERENCIA_PENDENTE.codigo(), s.codigo(), "conta", v.id(), v.url(), v.nome(), null, null, null,
                            v.moeda(), null, null, null, null, null, v.ultimaConferencia(), v.diferencaPendente(), null, null, null, null))));
            CalculoSituacao.conferenciaAntiga(hoje, e.ultima(), e.marco()).ifPresent(s -> sinais.add(new Sinal(s, Codigo.CONFERENCIA_ANTIGA,
                    v.nome(), new SinalVisto(Codigo.CONFERENCIA_ANTIGA.codigo(), s.codigo(), "conta", v.id(), v.url(), v.nome(), null, null,
                            null, v.moeda(), null, null, null, null, null, v.ultimaConferencia(), null, v.valeAte(), null, null, null))));
        }
    }

    /**
     * Cada mês fechado pela mesma soma da quebra por categoria de {@code resumo_periodo}, em competência e sem
     * âmbito. Com histórico parcial nos meses, a tendência não é avaliada: um mês antes do marco parece menor.
     */
    private void categorias(UUID dono, Currency moeda, LocalDate hoje, List<Sinal> sinais, List<String> ressalvas) {
        List<YearMonth> meses = CalculoSituacao.mesesFechados(hoje);
        LocalDate inicio = meses.get(0).atDay(1);
        Map<UUID, Conta> contas = new HashMap<>();
        estrutura.contas(dono).forEach(c -> contas.put(c.id(), c));
        Optional<Map.Entry<UUID, LocalDate>> parcial = estrutura.marcos(dono).entrySet().stream()
                .filter(m -> m.getValue().isAfter(inicio) && contas.containsKey(m.getKey()) && !contas.get(m.getKey()).arquivada())
                .max(Map.Entry.comparingByValue());
        if (parcial.isPresent()) {
            ressalvas.add("categoria_subindo não foi avaliado: os meses fechados (" + meses.get(0) + " a " + meses.get(meses.size() - 1)
                    + ") têm histórico parcial, porque a conta " + contas.get(parcial.get().getKey()).nome() + " só está coberta desde "
                    + parcial.get().getValue() + ".");
            return;
        }
        Map<UUID, List<Long>> porCategoria = new HashMap<>();
        for (int i = 0; i < meses.size(); i++) {
            YearMonth mes = meses.get(i);
            List<FatoPeriodo> fatos = lancamentos.fatosDoPeriodo(dono, mes.atDay(1), mes.atEndOfMonth(), null);
            for (CalculoResumo.LinhaQuebra linha : CalculoResumo.quebrar(fatos, Quebra.CATEGORIA,
                    f -> new Grupo(f.conta().toString(), f.conta().toString(), ""), moeda)) {
                UUID conta = UUID.fromString(linha.id());
                Conta c = contas.get(conta);
                if (c == null || c.tipo() != TipoConta.CATEGORIA || c.arquivada()) {
                    continue;
                }
                List<Long> valores = porCategoria.computeIfAbsent(conta, k -> new ArrayList<>(List.of(0L, 0L, 0L)));
                valores.set(i, linha.saidas().centavos());
            }
        }
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono);
        porCategoria.forEach((conta, valores) -> CalculoSituacao.categoriaSubindo(valores).ifPresent(t -> {
            Conta c = contas.get(conta);
            String codigo = codigos.get(conta);
            String url = codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
            Severidade s = CalculoSituacao.severidadeCategoria();
            sinais.add(new Sinal(s, Codigo.CATEGORIA_SUBINDO, c.nome(), new SinalVisto(Codigo.CATEGORIA_SUBINDO.codigo(), s.codigo(),
                    "categoria", codigo, url, c.nome(), null, null, null, moeda.getCurrencyCode(), null, null, null, null, null, null, null,
                    null, meses(meses, t, moeda), t.altas().stream().map(a -> a.toPlainString()).toList(), null)));
        }));
    }

    private static List<MesVisto> meses(List<YearMonth> meses, Tendencia t, Currency moeda) {
        List<MesVisto> vistos = new ArrayList<>();
        for (int i = 0; i < meses.size(); i++) {
            vistos.add(new MesVisto(meses.get(i).toString(), Dinheiro.deCentavos(t.saidasCentavos().get(i), moeda).texto()));
        }
        return vistos;
    }
}
