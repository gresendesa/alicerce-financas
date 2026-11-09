package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioCompromissos.OcorrenciaGravada;
import tools.alicerce.financeiro.aplicacao.RepositorioCompromissos.Pagamento;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.CalculoCompromisso;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Ocorrencia;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;
import tools.alicerce.financeiro.dominio.SituacaoOcorrencia;
import tools.alicerce.financeiro.dominio.TipoCompromisso;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** O que as capacidades de compromisso têm em comum: achar o compromisso e a ocorrência, vincular o pagamento e mostrar. */
@Component
class Compromissos {

    /** Sem ocorrência informada, um pagamento só escolhe sozinho entre as que vencem até tantos dias depois dele. */
    static final int DIAS_ANTECIPACAO = 20;
    /** Até onde um recorrente sem fim é mostrado à frente. */
    static final int MESES_A_FRENTE = 12;

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final Pattern NUMERO = Pattern.compile("[0-9]{1,4}");

    private final RepositorioCompromissos compromissos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioDono donos;
    private final RepositorioLancamentos lancamentos;
    private final Referencias referencias;
    private final Enderecos enderecos;

    Compromissos(RepositorioCompromissos compromissos, RepositorioEstrutura estrutura, RepositorioDono donos,
            RepositorioLancamentos lancamentos, Referencias referencias, Enderecos enderecos) {
        this.compromissos = compromissos;
        this.estrutura = estrutura;
        this.donos = donos;
        this.lancamentos = lancamentos;
        this.referencias = referencias;
        this.enderecos = enderecos;
    }

    LocalDate hoje(UUID dono) {
        return Datas.hoje(RELOGIO, donos.buscar(dono).orElseThrow().fuso());
    }

    /** O compromisso por id, id curto, URL, nome ou alias. Encerrados entram: um pagamento atrasado ainda pode chegar. */
    Compromisso resolver(UUID dono, String texto, String campo) {
        return referencias.compromisso(dono, texto, campo, compromissos.compromissos(dono));
    }

    /** As ocorrências até {@code ate}; num compromisso com fim, {@code ate} nulo traz todas. */
    List<Ocorrencia> ocorrencias(Compromisso c, Map<Integer, List<RegistroOcorrencia>> registros, LocalDate hoje, LocalDate ate) {
        return CalculoCompromisso.ocorrencias(c, registros, hoje, c.finito() ? ate : ate == null ? hoje.plusMonths(MESES_A_FRENTE) : ate);
    }

    /** Todas as de um compromisso com fim; num recorrente, até um ano à frente. */
    List<Ocorrencia> todas(Compromisso c, Map<Integer, List<RegistroOcorrencia>> registros, LocalDate hoje) {
        return ocorrencias(c, registros, hoje, null);
    }

    // ---- pagamento vinculado ----

    /**
     * Vincula o lançamento a uma ocorrência e grava. Sem {@code ocorrencia}, escolhe a única em aberto que
     * vence até {@value #DIAS_ANTECIPACAO} dias depois do pagamento; com mais de uma, pergunta.
     */
    OcorrenciaVinculada vincular(OperacaoEmCurso operacao, String textoCompromisso, String textoOcorrencia, Lancamento pagamento,
            DirecaoCompromisso direcao) {
        UUID dono = operacao.dono();
        Compromisso c = resolver(dono, textoCompromisso, "compromisso");
        if (c.direcao() != direcao) {
            throw ErroTipado.validacao("compromisso", "O compromisso '" + c.nome() + "' é de " + c.direcao().codigo()
                    + (direcao == DirecaoCompromisso.SAIDA ? ": um recebimento se registra com registrar_ganho." : ": um pagamento se registra com registrar_gasto."));
        }
        if (pagamento.parcelas() > 1) {
            throw ErroTipado.validacao("compromisso", "Compra parcelada no cartão não paga compromisso: as parcelas já estão nas faturas. "
                    + "Registre a compra sem compromisso, ou o pagamento de cada parcela à vista.");
        }
        Map<Integer, List<RegistroOcorrencia>> registros = compromissos.registros(c.id());
        LocalDate hoje = hoje(dono);
        int numero = escolher(dono, c, registros, textoOcorrencia, pagamento.data(), hoje);
        // Paga em parte continua em aberto e aceita outro pagamento; só a quitada recusa.
        Ocorrencia atual = CalculoCompromisso.ocorrencia(c, numero, registros.get(numero), hoje);
        if (atual.situacao() == SituacaoOcorrencia.PAGA) {
            throw jaPaga(dono, c, numero, atual);
        }
        if (atual.situacao() == SituacaoOcorrencia.PAGA_ANTES) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "ocorrencia", "A parcela " + numero + " de '" + c.nome()
                    + "' foi paga antes de o compromisso entrar no sistema.");
        }
        gravar(operacao, c, numero, RegistroOcorrencia.Situacao.PAGA, pagamento.id(), null, null);
        return vinculada(dono, c, numero, hoje);
    }

    /** Uma correção leva junto o vínculo: o lançamento novo paga o que o antigo pagava. Chamar antes de estornar o antigo. */
    void moverPagamentos(OperacaoEmCurso operacao, List<Pagamento> pagos, UUID novo) {
        Map<UUID, Compromisso> porId = compromissos.compromissos(operacao.dono()).stream().collect(Collectors.toMap(Compromisso::id, c -> c));
        for (Pagamento p : pagos) {
            gravar(operacao, porId.get(p.compromisso()), p.numero(), RegistroOcorrencia.Situacao.PAGA, novo, null, p.observacao());
        }
    }

    /** O que o lançamento paga agora; chame antes de estorná-lo. */
    List<Pagamento> pagamentosDe(UUID lancamento) {
        return compromissos.pagamentosDoLancamento(lancamento);
    }

    void gravar(OperacaoEmCurso operacao, Compromisso c, int numero, RegistroOcorrencia.Situacao situacao, UUID lancamento,
            LocalDate novaData, String observacao) {
        UUID id = UUID.randomUUID();
        compromissos.inserirOcorrencia(operacao, new OcorrenciaGravada(id, c.id(), numero, c.vencimento(numero), situacao, lancamento,
                novaData, observacao));
        operacao.ocorrenciaRegistrada(id);
    }

    OcorrenciaVinculada vinculada(UUID dono, Compromisso c, int numero, LocalDate hoje) {
        Map<Integer, List<RegistroOcorrencia>> registros = compromissos.registros(c.id());
        List<Ocorrencia> todas = todas(c, registros, hoje);
        CalculoCompromisso.Saldo saldo = CalculoCompromisso.saldo(c, todas);
        Ocorrencia o = todas.stream().filter(x -> x.numero() == numero).findFirst()
                .orElseGet(() -> CalculoCompromisso.ocorrencia(c, numero, registros.get(numero), hoje));
        Currency moeda = Currency.getInstance(c.moeda());
        ReferenciaCurta ref = referencia(dono, c.id());
        return new OcorrenciaVinculada(ref.id(), enderecos.url(ref), c.nome(), numero, parcela(c, numero), o.vencimento().toString(),
                texto(o.valorPrevistoCentavos(), moeda), o.situacao().codigo(), o.valorPagoCentavos() == null ? null : texto(o.valorPagoCentavos(), moeda),
                o.diferencaCentavos() == null ? null : texto(o.diferencaCentavos(), moeda),
                o.valorRestanteCentavos() == null ? null : texto(o.valorRestanteCentavos(), moeda),
                saldo.valorRestanteCentavos() == null ? null : texto(saldo.valorRestanteCentavos(), moeda), c.finito() ? saldo.emAberto() : null);
    }

    /** O número da ocorrência pedida: um número, um mês (AAAA-MM) ou uma data; sem texto, a única em aberto perto da data. */
    int escolher(UUID dono, Compromisso c, Map<Integer, List<RegistroOcorrencia>> registros, String texto, LocalDate data, LocalDate hoje) {
        LocalDate ate = (data.isAfter(hoje) ? data : hoje).plusDays(400);
        List<Ocorrencia> lista = ocorrencias(c, registros, hoje, ate);
        if (texto != null && !texto.isBlank()) {
            String limpo = texto.strip();
            Optional<Ocorrencia> achada;
            if (NUMERO.matcher(limpo).matches()) {
                int numero = Integer.parseInt(limpo);
                achada = lista.stream().filter(o -> o.numero() == numero).findFirst();
            } else if (limpo.length() == 7) {
                YearMonth mes = lerMes(limpo);
                achada = lista.stream().filter(o -> YearMonth.from(o.vencimentoOriginal()).equals(mes) || YearMonth.from(o.vencimento()).equals(mes))
                        .findFirst();
            } else {
                LocalDate dia = Datas.ler(limpo, "ocorrencia");
                achada = lista.stream().filter(o -> o.vencimentoOriginal().equals(dia) || o.vencimento().equals(dia)).findFirst();
            }
            return achada.map(Ocorrencia::numero).orElseThrow(() -> ErroTipado.referencia(CodigoErro.REFERENCIA_INEXISTENTE, "ocorrencia",
                    "'" + c.nome() + "' não tem ocorrência em '" + limpo + "'.", candidatos(dono, c, abertas(lista)),
                    "Use o número da parcela, o mês (AAAA-MM) ou a data do vencimento."));
        }
        List<Ocorrencia> abertas = abertas(lista);
        if (abertas.isEmpty()) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "ocorrencia", "'" + c.nome() + "' não tem ocorrência em aberto.");
        }
        List<Ocorrencia> perto = abertas.stream().filter(o -> !o.vencimento().isAfter(data.plusDays(DIAS_ANTECIPACAO))).toList();
        if (perto.size() > 1) {
            throw ErroTipado.referencia(CodigoErro.REFERENCIA_AMBIGUA, "ocorrencia",
                    "'" + c.nome() + "' tem " + perto.size() + " ocorrências em aberto até " + data.plusDays(DIAS_ANTECIPACAO) + ".",
                    candidatos(dono, c, perto), "Pergunte ao dono qual parcela este pagamento quita e envie ocorrencia.");
        }
        return (perto.isEmpty() ? abertas.get(0) : perto.get(0)).numero();
    }

    private static List<Ocorrencia> abertas(List<Ocorrencia> lista) {
        return lista.stream().filter(o -> o.situacao().emAberto()).sorted(Comparator.comparing(Ocorrencia::vencimento)).toList();
    }

    private static YearMonth lerMes(String texto) {
        try {
            return YearMonth.parse(texto);
        } catch (DateTimeParseException invalido) {
            throw ErroTipado.validacao("ocorrencia", "Use o número da parcela, o mês (AAAA-MM) ou a data do vencimento.");
        }
    }

    private List<ErroTipado.Candidato> candidatos(UUID dono, Compromisso c, List<Ocorrencia> lista) {
        Currency moeda = Currency.getInstance(c.moeda());
        ReferenciaCurta ref = referencia(dono, c.id());
        return lista.stream().limit(6).map(o -> new ErroTipado.Candidato(String.valueOf(o.numero()),
                parcela(c, o.numero()) + " vence " + o.vencimento() + ", " + texto(o.valorPrevistoCentavos(), moeda) + " (" + o.situacao().codigo() + ")",
                enderecos.url(ref))).toList();
    }

    private ErroTipado jaPaga(UUID dono, Compromisso c, int numero, Ocorrencia pago) {
        Map<UUID, String> codigos = lancamentos.codigos(List.of(pago.lancamento()));
        String codigo = codigos.get(pago.lancamento());
        String url = codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
        return ErroTipado.referencia(CodigoErro.CONFLITO, "ocorrencia", "A ocorrência " + parcela(c, numero) + " de '" + c.nome()
                + "' já está quitada.",
                List.of(new ErroTipado.Candidato(codigo, "pagamento de " + pago.dataPagamento(), url)),
                "Mostre ao dono o pagamento existente. Se este pagamento é de outra parcela, envie ocorrencia.");
    }

    // ---- saídas ----

    ReferenciaCurta referencia(UUID dono, UUID compromisso) {
        return estrutura.referenciaDe(dono, TipoReferencia.COMPROMISSO, compromisso).flatMap(c -> ReferenciaCurta.ler(c, TipoReferencia.MODULO))
                .orElseThrow();
    }

    static String parcela(Compromisso c, int numero) {
        return c.tipo() == TipoCompromisso.PARCELADO ? numero + "/" + c.parcelas() : String.valueOf(numero);
    }

    static String texto(long centavos, Currency moeda) {
        return Dinheiro.deCentavos(centavos, moeda).texto();
    }

    /** O compromisso com o saldo e a próxima ocorrência. */
    CompromissoVisto ver(UUID dono, Compromisso c, Map<Integer, List<RegistroOcorrencia>> registros, LocalDate hoje) {
        Map<UUID, String> contas = estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Conta::nome));
        Map<UUID, String> meios = estrutura.meios(dono).stream().collect(Collectors.toMap(MeioPagamento::id, MeioPagamento::nome));
        Map<UUID, String> ambitos = estrutura.ambitos(dono).stream().collect(Collectors.toMap(Ambito::id, Ambito::nome));
        List<Ocorrencia> todas = todas(c, registros, hoje);
        CalculoCompromisso.Saldo saldo = CalculoCompromisso.saldo(c, todas);
        Currency moeda = Currency.getInstance(c.moeda());
        ReferenciaCurta ref = referencia(dono, c.id());
        String situacao = c.encerrado() ? "encerrado" : c.finito() && saldo.emAberto() == 0 ? "quitado" : "ativo";
        CompromissoVisto.SaldoVisto saldoVisto = new CompromissoVisto.SaldoVisto(saldo.pagas(), saldo.puladas(), saldo.emAberto(),
                saldo.atrasadas(), saldo.total(), texto(saldo.valorPagoCentavos(), moeda),
                saldo.valorRestanteCentavos() == null ? null : texto(saldo.valorRestanteCentavos(), moeda),
                texto(saldo.valorAtrasadoCentavos(), moeda), saldo.pagasEmParte());
        return new CompromissoVisto(ref.id(), enderecos.url(ref), c.nome(), c.aliases(), c.direcao().codigo(), c.tipo().codigo(), c.referencia(),
                c.contraparte(), texto(c.valorCentavos(), moeda), c.valorTotalCentavos() == null ? null : texto(c.valorTotalCentavos(), moeda),
                c.moeda(), c.tipo() == TipoCompromisso.UNICO ? null : c.frequencia().codigo(), c.primeiroVencimento().toString(), c.parcelas(),
                c.parcelasPagasAntes() == 0 ? null : c.parcelasPagasAntes(), c.fim() == null ? null : c.fim().toString(),
                c.categoria() == null ? null : contas.get(c.categoria()), c.conta() == null ? null : contas.get(c.conta()),
                c.meioPagamento() == null ? null : meios.get(c.meioPagamento()), c.ambito() == null ? null : ambitos.get(c.ambito()),
                c.observacao(), situacao, saldoVisto, saldo.proxima().map(o -> vista(c, o, Map.of())).orElse(null), null, null);
    }

    /** As ocorrências como as saídas mostram. */
    List<OcorrenciaVista> vistas(Compromisso c, Collection<Ocorrencia> lista) {
        List<UUID> pagos = lista.stream().flatMap(o -> o.pagamentos().stream()).map(Ocorrencia.PagamentoOcorrencia::lancamento).distinct().toList();
        Map<UUID, String> codigos = pagos.isEmpty() ? Map.of() : lancamentos.codigos(pagos);
        return lista.stream().map(o -> vista(c, o, codigos)).toList();
    }

    OcorrenciaVista vista(Compromisso c, Ocorrencia o, Map<UUID, String> codigos) {
        Currency moeda = Currency.getInstance(c.moeda());
        String codigo = o.lancamento() == null ? null : codigos.get(o.lancamento());
        List<OcorrenciaVista.PagamentoVisto> pagamentos = o.pagamentos().size() < 2 ? null : o.pagamentos().stream()
                .map(p -> new OcorrenciaVista.PagamentoVisto(codigos.get(p.lancamento()), urlDe(codigos.get(p.lancamento())),
                        p.data() == null ? null : p.data().toString(), texto(p.valorCentavos(), moeda)))
                .toList();
        return new OcorrenciaVista(o.numero(), parcela(c, o.numero()), o.vencimento().toString(),
                o.adiada() ? o.vencimentoOriginal().toString() : null, texto(o.valorPrevistoCentavos(), moeda), o.situacao().codigo(), codigo,
                urlDe(codigo), o.dataPagamento() == null ? null : o.dataPagamento().toString(),
                o.valorPagoCentavos() == null ? null : texto(o.valorPagoCentavos(), moeda),
                o.diferencaCentavos() == null ? null : texto(o.diferencaCentavos(), moeda), o.observacao(),
                o.valorRestanteCentavos() == null ? null : texto(o.valorRestanteCentavos(), moeda), pagamentos);
    }

    private String urlDe(String codigo) {
        return codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
    }

    /** As próximas {@code quantas} em aberto. */
    List<OcorrenciaVista> proximas(Compromisso c, Map<Integer, List<RegistroOcorrencia>> registros, LocalDate hoje, int quantas) {
        List<Ocorrencia> abertas = abertas(todas(c, registros, hoje)).stream().limit(quantas).toList();
        return vistas(c, abertas);
    }

    Dono dono(UUID dono) {
        return donos.buscar(dono).orElseThrow();
    }

    /** As ocorrências em aberto, de todos os compromissos, com vencimento até {@code ate}. */
    List<Aberta> abertas(UUID dono, LocalDate hoje, LocalDate ate) {
        Map<UUID, Map<Integer, List<RegistroOcorrencia>>> registros = compromissos.registrosDoDono(dono);
        List<Aberta> lista = new ArrayList<>();
        for (Compromisso c : compromissos.compromissos(dono)) {
            for (Ocorrencia o : ocorrencias(c, registros.getOrDefault(c.id(), Map.of()), hoje, ate)) {
                if (o.situacao().emAberto() && !o.vencimento().isAfter(ate)) {
                    lista.add(new Aberta(c, o));
                }
            }
        }
        lista.sort(Comparator.comparing((Aberta a) -> a.ocorrencia().vencimento()).thenComparing(a -> a.compromisso().nome()));
        return lista;
    }

    /** Uma ocorrência em aberto e o compromisso dela. */
    record Aberta(Compromisso compromisso, Ocorrencia ocorrencia) {

        boolean saida() {
            return compromisso.direcao() == DirecaoCompromisso.SAIDA;
        }
    }

    /** A ocorrência na agenda e nos candidatos da conferência, pelo que falta dela (a paga em parte, pelo restante). */
    ItemAgenda item(UUID dono, Aberta a, LocalDate hoje) {
        Compromisso c = a.compromisso();
        Ocorrencia o = a.ocorrencia();
        ReferenciaCurta ref = referencia(dono, c.id());
        Currency moeda = Currency.getInstance(c.moeda());
        return new ItemAgenda("compromisso", ref.id(), enderecos.url(ref), c.nome(), c.direcao().codigo(), o.vencimento().toString(),
                texto(o.valorRestanteCentavos(), moeda), c.moeda(), o.situacao().codigo(), parcela(c, o.numero()),
                c.referencia(), c.contraparte(), o.adiada() ? o.vencimentoOriginal().toString() : null,
                o.pagaEmParte() ? texto(o.valorPrevistoCentavos(), moeda) : null, o.pagaEmParte() ? texto(o.valorPagoCentavos(), moeda) : null);
    }
}
