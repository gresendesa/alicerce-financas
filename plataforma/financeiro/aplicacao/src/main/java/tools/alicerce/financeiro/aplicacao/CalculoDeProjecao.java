package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CalculoCompromisso;
import tools.alicerce.financeiro.dominio.CalculoMeta;
import tools.alicerce.financeiro.dominio.CalculoProjecao;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Coluna;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Fluxo;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.CicloFatura;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.FatoPeriodo;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.Ocorrencia;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.Repartir;
import tools.alicerce.financeiro.dominio.TipoCompromisso;
import tools.alicerce.financeiro.dominio.TipoConta;

/**
 * Monta {@code projetar} a partir de parâmetros já resolvidos: busca o saldo de partida, os fluxos conhecidos e os
 * gastos como {@code cortes_necessarios}, resolve as hipóteses pelo código da escrita sem gravar nada, e deixa todas as
 * contas para {@link CalculoProjecao}. Só lê.
 */
@Component
class CalculoDeProjecao {

    static final String NOTA_ESTIMATIVAS = "p50 é a estimativa otimista (a mediana do gasto variável de cada categoria) e p80 a "
            + "pessimista (o percentil 80 de cada categoria, somado). A soma dos percentis 80 superestima de propósito: p80 não é previsão, "
            + "é o lado prudente. Mostre as duas e diga qual é qual.";
    static final String NOTA_CAIXA = "Visão de caixa: cada valor conta no mês em que sai da conta. O que vai para o cartão conta no mês em "
            + "que a fatura vence, pelos dias de fechamento e vencimento de cada cartão; o gasto variável vai para o cartão na proporção "
            + "dos meses de referência. O que vence depois do horizonte fica fora.";
    static final String NOTA_PAPEL = "Todos os números vêm do servidor: apresente-os como vieram, sem somar nem recalcular. Para responder "
            + "\"posso comprar isso?\", chame de novo com a compra como cenário (até três por chamada), e compare pela diferença para a "
            + "linha de base e pelo menor saldo.";
    static final String NOTA_METAS = "Em cada cenário, metas traz a data projetada de cada meta ativa: o primeiro mês em que o progresso de "
            + "hoje mais os aportes planejados no cenário alcança o alvo, contra a data alvo. A linha de base não tem aporte planejado; "
            + "para responder \"se eu guardar tanto por mês, chego lá?\", chame com a hipótese aporte_meta.";
    static final String NOTA_PISO = "Sem piso declarado, não há meses abaixo do piso. O dono pode declarar com quanto quer fechar cada mês "
            + "com declarar (tipo piso_periodo).";
    static final String NOTA_PARCELAS = "Em cada mês, renda é o que entra nele (no mês corrente, também o já recebido) e parcelas são as de "
            + "compras parceladas que vencem nas faturas do mês, pagas ou não, mais as de compromissos parcelados. As parcelas já estão dentro "
            + "de faturas e saídas: nunca some. comprometimento é parcelas sobre renda; mês sem renda não é avaliado.";
    static final String NOTA_REGRAS = "violacoes lista, em cada cenário, as regras declaradas que ele quebra, mês a mês, pelo lado prudente "
            + "(saldo final p80): piso_periodo, reserva_minima, teto_parcelas e meta_protegida. Uma meta protegida é violada quando o saldo final "
            + "p80 de um mês até a data alvo não cobre o piso (zero sem piso) mais o aporte mensal necessário dela. Mostre as violações ao dono "
            + "com os números, sem julgar.";
    static final String NOTA_SEM_REGRAS = "Sem reserva mínima nem teto de parcelas declarados, não há meses abaixo da reserva nem violação de "
            + "teto. O dono pode declarar os dois com declarar (tipos reserva_minima e teto_parcelas).";

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioCartoes cartoes;
    private final Compromissos compromissos;
    private final ObterAgenda agenda;
    private final CalculoDeCortes cortes;
    private final Faturas faturas;
    private final Referencias referencias;
    private final DefinirCompromisso definirCompromisso;
    private final Metas metas;
    private final LeituraDoPerfil perfil;

    CalculoDeProjecao(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, RepositorioCartoes cartoes, Compromissos compromissos,
            ObterAgenda agenda, CalculoDeCortes cortes, Faturas faturas, Referencias referencias, DefinirCompromisso definirCompromisso,
            Metas metas, LeituraDoPerfil perfil) {
        this.perfil = perfil;
        this.metas = metas;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.cartoes = cartoes;
        this.compromissos = compromissos;
        this.agenda = agenda;
        this.cortes = cortes;
        this.faturas = faturas;
        this.referencias = referencias;
        this.definirCompromisso = definirCompromisso;
    }

    /** Uma hipótese resolvida: o que entra no domínio e o que o assistente vê. */
    private record Resolvida(CalculoProjecao.Hipotese hipotese, ProjecaoVista.HipoteseVista vista) {}

    /**
     * O que a linha de base e os cenários têm em comum, já lido.
     *
     * @param ressalvas as do saldo de partida e dos fluxos; as de confiança entram depois
     */
    record Preparo(LocalDate hoje, LocalDate fim, List<Conta> todas, Map<UUID, Conta> contas, Map<UUID, String> codigos,
            CalculoDeCortes.Partida partida, CalculoDeCortes.BaseVariavel base, CalculoProjecao.Base b, List<Meta> emAndamento,
            Map<UUID, Long> progressos, Long piso, List<CortesVisto.Ressalva> ressalvas) {}

    /** A linha de base, para {@code situacao}: o mesmo cálculo de {@code projetar}, sem cenários. */
    record LinhaDeBase(Preparo preparo, CalculoProjecao.Resultado resultado) {}

    LinhaDeBase linhaDeBase(UUID dono, Currency moeda, ParametrosProjecao p) {
        Preparo preparo = preparar(dono, moeda, p);
        return new LinhaDeBase(preparo, CalculoProjecao.projetar(preparo.b(), List.of()));
    }

    Preparo preparar(UUID dono, Currency moeda, ParametrosProjecao p) {
        LocalDate hoje = LocalDate.parse(p.hoje());
        YearMonth mes = YearMonth.from(hoje);
        LocalDate fim = mes.plusMonths(p.horizonteMeses() - 1L).atEndOfMonth();
        List<Conta> todas = estrutura.contas(dono);
        Map<UUID, Conta> contas = todas.stream().collect(Collectors.toMap(Conta::id, Function.identity()));
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono);
        List<CortesVisto.Ressalva> ressalvas = new ArrayList<>();

        // Saldo de partida (decisão 4): o mesmo de cortes_necessarios.
        CalculoDeCortes.Partida partida = cortes.partida(dono, moeda, hoje, todas, codigos, ressalvas);

        // Fluxos conhecidos (decisão 2): ocorrências em aberto até o fim do horizonte e o restante das faturas gravadas.
        List<Fluxo> fluxos = new ArrayList<>();
        Set<String> outraMoeda = new LinkedHashSet<>();
        for (Compromissos.Aberta a : compromissos.abertas(dono, hoje, fim)) {
            if (!a.compromisso().moeda().equals(moeda.getCurrencyCode())) {
                outraMoeda.add(a.compromisso().nome());
                continue;
            }
            fluxos.add(new Fluxo(a.saida() ? Coluna.SAIDA : Coluna.ENTRADA, a.ocorrencia().vencimento(), a.ocorrencia().valorRestanteCentavos()));
        }
        if (!outraMoeda.isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("compromisso_outra_moeda", "Fora dos fluxos, por estarem em outra moeda: "
                    + String.join(", ", outraMoeda) + "."));
        }
        agenda.faturasEmAberto(dono, moeda, hoje).forEach(f -> fluxos.add(new Fluxo(Coluna.FATURA, f.venceEm(), f.restanteCentavos())));

        // Gasto variável (decisão 3): a base de cortes_necessarios, com a parte de cada cartão.
        CalculoDeCortes.BaseVariavel base = cortes.baseVariavel(dono, contas, mes);
        Map<UUID, CartaoCredito> cartoesDoDono = cartoes.cartoes(dono).stream().collect(Collectors.toMap(CartaoCredito::conta, c -> c));
        List<CalculoProjecao.Categoria> categorias = CalculoProjecao.categorias(hoje, base.porMes().getOrDefault(mes, List.of()),
                base.referencia(), cartaoDoLancamento(dono, base.periodo(), cartoesDoDono.keySet()));
        Long piso = p.piso() == null ? null : Dinheiro.ler(p.piso(), moeda, "piso").centavos();
        // Metas: as em andamento na moeda do dono, com o progresso de hoje.
        Map<UUID, Long> progressos = metas.progressos(dono);
        List<Meta> emAndamento = metas.emAndamento(dono).stream().filter(m -> m.moeda().equals(moeda.getCurrencyCode())).toList();
        Set<String> protegidas = Set.copyOf(p.regras().metasProtegidas());
        List<CalculoProjecao.MetaBase> metasBase = emAndamento.stream().map(m -> new CalculoProjecao.MetaBase(m.id(),
                progressos.getOrDefault(m.id(), 0L), m.alvoCentavos(), m.dataAlvo(), protegidas.contains(m.id().toString()))).toList();
        // Regras do perfil: parcelas e renda de cada mês, a reserva e o teto declarados.
        Long reserva = p.regras().reserva() == null ? null : Dinheiro.ler(p.regras().reserva(), moeda, "reserva").centavos();
        BigDecimal teto = p.regras().teto() == null ? null : new BigDecimal(p.regras().teto());
        long rendaRecebida = perfil.rendaESaidas(dono, moeda, mes.atDay(1), hoje).renda();
        CalculoProjecao.Perfil regras = new CalculoProjecao.Perfil(reserva, teto,
                perfil.parcelasPorMes(dono, moeda, mes, YearMonth.from(fim), hoje), rendaRecebida);
        CalculoProjecao.Base b = new CalculoProjecao.Base(hoje, p.horizonteMeses(), partida.centavos(), fluxos, categorias,
                base.validos().size(), piso, vencimentos(cartoesDoDono), metasBase, regras);
        return new Preparo(hoje, fim, todas, contas, codigos, partida, base, b, emAndamento, progressos, piso, ressalvas);
    }

    ProjecaoVista calcular(UUID dono, Currency moeda, ParametrosProjecao p, OffsetDateTime agora) {
        Preparo preparo = preparar(dono, moeda, p);
        LocalDate hoje = preparo.hoje();
        LocalDate fim = preparo.fim();
        List<Conta> todas = preparo.todas();
        Map<UUID, Conta> contas = preparo.contas();
        CalculoDeCortes.Partida partida = preparo.partida();
        CalculoDeCortes.BaseVariavel base = preparo.base();
        CalculoProjecao.Base b = preparo.b();
        List<Meta> emAndamento = preparo.emAndamento();
        Map<UUID, Long> progressos = preparo.progressos();
        Long piso = preparo.piso();
        List<CortesVisto.Ressalva> ressalvas = preparo.ressalvas();
        Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moeda).texto();
        Map<UUID, MetaVista> vistas = new HashMap<>();
        emAndamento.forEach(m -> vistas.put(m.id(), metas.ver(dono, m, progressos.getOrDefault(m.id(), 0L), hoje)));

        CalculoProjecao.Resultado linha = CalculoProjecao.projetar(b, List.of());
        ProjecaoVista.CenarioVisto linhaBase = cenario("Linha de base", List.of(), linha, null, dinheiro, vistas);
        List<ProjecaoVista.CenarioVisto> cenarios = new ArrayList<>();
        for (int i = 0; i < p.cenarios().size(); i++) {
            Projetar.Cenario c = p.cenarios().get(i);
            List<Resolvida> hipoteses = new ArrayList<>();
            for (int j = 0; j < c.hipoteses().size(); j++) {
                String prefixo = "cenarios[" + i + "].hipoteses[" + j + "].";
                Projetar.Hipotese h = c.hipoteses().get(j);
                hipoteses.add(noCenario(c.rotulo(), prefixo, () -> resolver(dono, moeda, hoje, fim, todas, h, prefixo, progressos)));
            }
            CalculoProjecao.Resultado r = CalculoProjecao.projetar(b, hipoteses.stream().map(Resolvida::hipotese).toList());
            cenarios.add(cenario(c.rotulo(), hipoteses.stream().map(Resolvida::vista).toList(), r, CalculoProjecao.diferenca(linha, r), dinheiro,
                    vistas));
        }

        cortes.confianca(dono, hoje, base.validos().size(), base, contas, ressalvas, "estimar o gasto variável", " Não há estimativa de "
                + "gasto variável: os saldos de cada mês não descontam o gasto do dia a dia (mercado, transporte, lazer) e ficam otimistas "
                + "demais. Repasse isso ao dono e não estime esse gasto por conta própria.");
        List<String> notas = new ArrayList<>(List.of(NOTA_ESTIMATIVAS, NOTA_CAIXA, NOTA_PAPEL));
        if (piso == null) {
            notas.add(NOTA_PISO);
        }
        if (!emAndamento.isEmpty()) {
            notas.add(NOTA_METAS);
        }
        notas.add(NOTA_PARCELAS);
        ParametrosProjecao.Regras regras = p.regras();
        notas.add(regras.reserva() == null && regras.teto() == null ? NOTA_SEM_REGRAS : NOTA_REGRAS);
        ressalvasDoPerfil(regras, linha, ressalvas);
        return new ProjecaoVista(agora.toString(), p.hoje(), fim.toString(), p.horizonteMeses(), moeda.getCurrencyCode(),
                dinheiro.apply(partida.centavos()), partida.contas(), b.historicoSuficiente(), base.validos().size(),
                tools.alicerce.financeiro.dominio.CalculoRitmo.MESES_PADRAO,
                piso == null ? null : new ProjecaoVista.Piso(dinheiro.apply(piso), p.operacaoPiso()), linhaBase, cenarios, ressalvas, notas,
                regras(dono, regras, hoje));
    }

    /** A reserva em meses sem gasto típico e os meses sem renda com teto declarado saem como ressalva, sem número inventado. */
    static void ressalvasDoPerfil(ParametrosProjecao.Regras regras, CalculoProjecao.Resultado linha, List<CortesVisto.Ressalva> ressalvas) {
        if (regras.reservaSemValor()) {
            ressalvas.add(new CortesVisto.Ressalva("reserva_sem_valor", "A reserva mínima é de " + regras.reservaMesesDeGasto() + " meses de "
                    + "gasto, e o gasto mensal típico precisa de " + tools.alicerce.financeiro.dominio.CalculoRitmo.MESES_MINIMOS + " meses de "
                    + "referência com histórico completo: os meses abaixo da reserva não foram avaliados. Não estime a reserva por conta própria."));
        }
        if (regras.teto() != null && !linha.mesesSemRenda().isEmpty()) {
            ressalvas.add(new CortesVisto.Ressalva("mes_sem_renda", "Sem renda prevista em " + String.join(", ",
                    linha.mesesSemRenda().stream().map(YearMonth::toString).toList()) + ": o teto de parcelas não foi avaliado nesses meses. "
                    + "A renda da projeção vem dos compromissos de entrada; o dono pode cadastrar a renda com definir_compromisso."));
        }
    }

    private ProjecaoVista.Regras regras(UUID dono, ParametrosProjecao.Regras r, LocalDate hoje) {
        Map<String, Meta> porId = new HashMap<>();
        metas.visiveis(dono).forEach(m -> porId.put(m.id().toString(), m));
        List<ProjecaoVista.MetaProtegidaVista> protegidas = new ArrayList<>();
        for (String id : r.metasProtegidas()) {
            Meta m = porId.get(id);
            if (m == null) {
                continue;
            }
            MetaVista v = metas.ver(dono, m, 0, hoje);
            protegidas.add(new ProjecaoVista.MetaProtegidaVista(v.id(), v.url(), m.nome(), m.dataAlvo() == null ? null : m.dataAlvo().toString()));
        }
        boolean temReserva = r.reserva() != null || r.reservaSemValor();
        return new ProjecaoVista.Regras(temReserva ? new ProjecaoVista.ReservaVista(r.reserva(), r.reservaMesesDeGasto(), r.gastoMensalTipico(),
                r.mesesGastoTipico(), r.operacaoReserva()) : null, r.teto() == null ? null : new ProjecaoVista.TetoVisto(r.teto(), r.operacaoTeto()),
                protegidas);
    }

    /** Erro numa hipótese: o campo ganha o caminho dela, e a mensagem diz de qual cenário é. */
    private static Resolvida noCenario(String rotulo, String prefixo, java.util.function.Supplier<Resolvida> resolver) {
        try {
            return resolver.get();
        } catch (ErroTipado e) {
            String onde = "No cenário '" + rotulo + "': ";
            if (!e.camposFaltando().isEmpty()) {
                throw ErroTipado.camposFaltando(e.camposFaltando().stream().map(c -> c.startsWith(prefixo) ? c : prefixo + c).toList(),
                        onde + (e.sugestao() == null ? "pergunte ao dono o que falta." : e.sugestao()));
            }
            String campo = e.campo() == null ? prefixo + "tipo" : e.campo().startsWith(prefixo) ? e.campo() : prefixo + e.campo();
            throw ErroTipado.referencia(e.codigo(), campo, onde + e.mensagem(), e.candidatos(), e.sugestao());
        }
    }

    private Resolvida resolver(UUID dono, Currency moeda, LocalDate hoje, LocalDate fim, List<Conta> todas, Projetar.Hipotese h, String prefixo,
            Map<UUID, Long> progressos) {
        if (h == null || Argumentos.vazio(h.tipo())) {
            throw ErroTipado.camposFaltando(List.of(prefixo + "tipo"), "Diga o que simular: compra, novo_compromisso, mudanca_renda, "
                    + "mudanca_categoria ou aporte_meta.");
        }
        return switch (h.tipo().strip()) {
            case "compra" -> compra(dono, moeda, hoje, fim, h, prefixo);
            case "novo_compromisso" -> novoCompromisso(dono, moeda, hoje, fim, h, prefixo);
            case "mudanca_renda" -> mudancaRenda(moeda, hoje, fim, h, prefixo);
            case "mudanca_categoria" -> mudancaCategoria(dono, hoje, fim, todas, h, prefixo);
            case "aporte_meta" -> aporteMeta(dono, moeda, hoje, fim, h, prefixo, progressos);
            default -> throw ErroTipado.validacao(prefixo + "tipo", "Tipo de hipótese inválido: use compra, novo_compromisso, mudanca_renda, "
                    + "mudanca_categoria ou aporte_meta.");
        };
    }

    /**
     * Uma compra pelas regras de {@code registrar_gasto}: o mesmo meio, as mesmas parcelas e, no crédito, cada parcela na
     * fatura que a escrita escolheria, respeitando o fechamento e as faturas já gravadas (decisão 6).
     */
    private Resolvida compra(UUID dono, Currency moeda, LocalDate hoje, LocalDate fim, Projetar.Hipotese h, String prefixo) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(h.valor())) {
            faltando.add(prefixo + "valor");
        }
        if (Argumentos.vazio(h.meioPagamento())) {
            faltando.add(prefixo + "meio_pagamento");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono quanto custa e como pretende pagar (o meio e, no crédito, em quantas parcelas).");
        }
        Dinheiro total = RegistroDeLancamento.positivo(h.valor().strip(), moeda, prefixo + "valor");
        MeioPagamento meio = referencias.meio(dono, h.meioPagamento(), prefixo + "meio_pagamento");
        Conta liquidacao = referencias.conta(dono, meio.contaLiquidacao().toString(), prefixo + "meio_pagamento", c -> true);
        RegistroDeLancamento.exigirMoedaDoDono(liquidacao, moeda, prefixo + "meio_pagamento");
        int parcelas = RegistrarGasto.parcelas(h.parcelas(), liquidacao, prefixo + "parcelas");
        LocalDate data = Argumentos.vazio(h.data()) ? hoje : Datas.ler(h.data().strip(), prefixo + "data");
        if (data.isBefore(hoje) || data.isAfter(fim)) {
            throw ErroTipado.validacao(prefixo + "data", "A compra simulada é de hoje (" + hoje + ") até o fim do horizonte (" + fim
                    + "). Compra já feita se registra com registrar_gasto.");
        }
        List<Fluxo> fluxos = new ArrayList<>();
        List<ProjecaoVista.ItemHipotese> itens = new ArrayList<>();
        if (liquidacao.tipo() == TipoConta.CARTAO_CREDITO) {
            CartaoCredito cartao = cartoes.cartao(liquidacao.id()).orElseThrow();
            Function<String, Optional<Fatura>> gravadas = faturas.faturasGravadas(cartao.conta());
            long[] valores = Repartir.parcelas(total.centavos(), parcelas);
            List<CicloFatura> ciclos = cartao.ciclosDaCompra(data, parcelas, gravadas);
            for (int i = 0; i < parcelas; i++) {
                LocalDate vence = CartaoCredito.vencimento(ciclos.get(i), gravadas);
                fluxos.add(parcelas > 1 ? Fluxo.parcela(Coluna.FATURA, vence, valores[i]) : new Fluxo(Coluna.FATURA, vence, valores[i]));
                itens.add(new ProjecaoVista.ItemHipotese(vence.toString(), Dinheiro.deCentavos(valores[i], moeda).texto(), "Fatura "
                        + liquidacao.nome() + " " + ciclos.get(i).periodo() + (parcelas > 1 ? ", parcela " + (i + 1) + "/" + parcelas : "")));
            }
        } else {
            fluxos.add(new Fluxo(Coluna.SAIDA, data, total.centavos()));
            itens.add(new ProjecaoVista.ItemHipotese(data.toString(), total.texto(), meio.nome()));
        }
        String resumo = "Compra de " + ResumoOperacao.dinheiro(total.texto(), moeda.getCurrencyCode()) + " em " + meio.nome()
                + (parcelas > 1 ? ", em " + parcelas + " parcelas" : "") + ", em " + data;
        return new Resolvida(new CalculoProjecao.Fluxos(fluxos), new ProjecaoVista.HipoteseVista("compra", resumo, itens));
    }

    /** Um compromisso pelas regras de {@code definir_compromisso}, com as ocorrências que venceriam no horizonte (decisão 6). */
    private Resolvida novoCompromisso(UUID dono, Currency moeda, LocalDate hoje, LocalDate fim, Projetar.Hipotese h, String prefixo) {
        String tipo = Argumentos.vazio(h.tipoCompromisso()) ? TipoCompromisso.RECORRENTE.codigo() : h.tipoCompromisso().strip();
        if (!Argumentos.vazio(h.primeiroVencimento()) && Datas.ler(h.primeiroVencimento().strip(), prefixo + "primeiro_vencimento").isBefore(hoje)) {
            throw ErroTipado.validacao(prefixo + "primeiro_vencimento", "O primeiro vencimento simulado é de hoje (" + hoje + ") em diante.");
        }
        DefinirCompromisso.Entrada entrada = new DefinirCompromisso.Entrada(null, null, null, h.direcao(), tipo, "Hipótese", null, h.valor(),
                null, h.parcelas(), null, h.frequencia(), h.primeiroVencimento(), h.fim(), null, null, null, null, null, null, null, null);
        Compromisso c;
        try {
            c = definirCompromisso.simular(dono, entrada);
        } catch (ErroTipado e) {
            if (!e.camposFaltando().isEmpty()) {
                throw ErroTipado.camposFaltando(e.camposFaltando().stream().map(campo -> "tipo".equals(campo) ? "tipo_compromisso" : campo).toList(),
                        "Pergunte ao dono se ele paga ou recebe, quanto, quando vence a primeira vez e, se for parcelado, em quantas parcelas.");
            }
            throw "tipo".equals(e.campo()) ? ErroTipado.validacao("tipo_compromisso", e.mensagem()) : e;
        }
        List<Fluxo> fluxos = new ArrayList<>();
        List<ProjecaoVista.ItemHipotese> itens = new ArrayList<>();
        boolean saida = c.direcao() == DirecaoCompromisso.SAIDA;
        for (Ocorrencia o : CalculoCompromisso.ocorrencias(c, Map.of(), hoje, fim)) {
            if (o.vencimento().isBefore(hoje) || o.vencimento().isAfter(fim)) {
                continue;
            }
            fluxos.add(saida && c.tipo() == TipoCompromisso.PARCELADO ? Fluxo.parcela(Coluna.SAIDA, o.vencimento(), o.valorPrevistoCentavos())
                    : new Fluxo(saida ? Coluna.SAIDA : Coluna.ENTRADA, o.vencimento(), o.valorPrevistoCentavos()));
            itens.add(new ProjecaoVista.ItemHipotese(o.vencimento().toString(), Dinheiro.deCentavos(o.valorPrevistoCentavos(), moeda).texto(),
                    c.tipo() == TipoCompromisso.PARCELADO ? "Parcela " + Compromissos.parcela(c, o.numero()) : saida ? "Saída" : "Entrada"));
        }
        String resumo = (saida ? "Novo compromisso a pagar de " : "Nova entrada de ")
                + ResumoOperacao.dinheiro(Dinheiro.deCentavos(c.valorCentavos(), moeda).texto(), moeda.getCurrencyCode())
                + (c.tipo() == TipoCompromisso.UNICO ? ", uma vez" : c.tipo() == TipoCompromisso.PARCELADO ? " em " + c.parcelas() + " parcelas"
                        : " por " + (c.frequencia().codigo().equals("anual") ? "ano" : "mês"))
                + ", a partir de " + c.primeiroVencimento() + (c.fim() == null ? "" : ", até " + c.fim());
        return new Resolvida(new CalculoProjecao.Fluxos(fluxos), new ProjecaoVista.HipoteseVista("novo_compromisso", resumo, itens));
    }

    /** A renda muda todo mês a partir de um mês; padrão, o mês seguinte. */
    private Resolvida mudancaRenda(Currency moeda, LocalDate hoje, LocalDate fim, Projetar.Hipotese h, String prefixo) {
        if (Argumentos.vazio(h.valor())) {
            throw ErroTipado.camposFaltando(List.of(prefixo + "valor"), "Pergunte ao dono quanto a renda muda por mês: positivo para aumento, "
                    + "negativo para perda.");
        }
        Dinheiro valor = Dinheiro.ler(h.valor().strip(), moeda, prefixo + "valor");
        if (valor.centavos() == 0) {
            throw ErroTipado.validacao(prefixo + "valor", "A mudança de renda não pode ser zero.");
        }
        YearMonth de = mesNoHorizonte(h.de(), YearMonth.from(hoje).plusMonths(1), hoje, fim, prefixo);
        String resumo = (valor.centavos() > 0 ? "Renda aumenta " : "Renda diminui ")
                + ResumoOperacao.dinheiro(valor.texto().replace("-", ""), moeda.getCurrencyCode()) + " por mês a partir de " + de;
        return new Resolvida(new CalculoProjecao.MudancaRenda(valor.centavos(), de), new ProjecaoVista.HipoteseVista("mudanca_renda", resumo, List.of()));
    }

    /** O gasto variável de uma categoria, com as subcategorias, muda em um percentual a partir de um mês; padrão, o mês de hoje. */
    private Resolvida mudancaCategoria(UUID dono, LocalDate hoje, LocalDate fim, List<Conta> todas, Projetar.Hipotese h, String prefixo) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(h.categoria())) {
            faltando.add(prefixo + "categoria");
        }
        if (Argumentos.vazio(h.percentual())) {
            faltando.add(prefixo + "percentual");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono em que categoria e em quantos por cento o gasto muda (negativo para gastar menos).");
        }
        Conta categoria = referencias.conta(dono, h.categoria(), prefixo + "categoria", c -> c.tipo() == TipoConta.CATEGORIA);
        BigDecimal percentual;
        try {
            percentual = new BigDecimal(h.percentual().strip());
        } catch (NumberFormatException e) {
            throw ErroTipado.validacao(prefixo + "percentual", "Percentual inválido: use um número com ponto, como \"-20\" ou \"12.5\".");
        }
        if (percentual.scale() > 2 || percentual.compareTo(BigDecimal.valueOf(-100)) < 0 || percentual.compareTo(BigDecimal.valueOf(1000)) > 0
                || percentual.signum() == 0) {
            throw ErroTipado.validacao(prefixo + "percentual", "O percentual vai de -100 a 1000, diferente de zero, com até duas casas.");
        }
        YearMonth de = mesNoHorizonte(h.de(), YearMonth.from(hoje), hoje, fim, prefixo);
        Set<UUID> ids = CalculoDeCortes.comSubcategorias(Set.of(categoria.id()), todas);
        String resumo = "Gasto variável em " + categoria.nome() + (percentual.signum() > 0 ? " aumenta " : " diminui ")
                + percentual.abs().stripTrailingZeros().toPlainString() + "% a partir de " + de;
        return new Resolvida(new CalculoProjecao.MudancaCategoria(ids, percentual, de), new ProjecaoVista.HipoteseVista("mudanca_categoria",
                resumo, List.of()));
    }

    /**
     * Aportes mensais numa meta, pelas regras da escrita de aporte: a meta ativa, o valor positivo na moeda dela. Cada aporte
     * sai do saldo no começo do mês (no corrente, hoje), e os aportes param quando a meta chega no alvo.
     */
    private Resolvida aporteMeta(UUID dono, Currency moeda, LocalDate hoje, LocalDate fim, Projetar.Hipotese h, String prefixo,
            Map<UUID, Long> progressos) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(h.meta())) {
            faltando.add(prefixo + "meta");
        }
        if (Argumentos.vazio(h.valor())) {
            faltando.add(prefixo + "valor");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono para qual meta e quanto ele quer guardar por mês.");
        }
        Meta meta = metas.resolver(dono, h.meta(), prefixo + "meta");
        if (!meta.emAndamento()) {
            throw ErroTipado.validacao(prefixo + "meta", "A meta '" + meta.nome() + "' está " + meta.situacao().codigo()
                    + ": só meta ativa recebe aporte.");
        }
        if (!meta.moeda().equals(moeda.getCurrencyCode())) {
            throw ErroTipado.validacao(prefixo + "meta", "A projeção é na moeda do dono, e a meta está em " + meta.moeda() + ".");
        }
        Dinheiro mensal = RegistroDeLancamento.positivo(h.valor().strip(), moeda, prefixo + "valor");
        YearMonth de = mesNoHorizonte(h.de(), YearMonth.from(hoje), hoje, fim, prefixo);
        YearMonth ate = Argumentos.vazio(h.ate()) ? YearMonth.from(fim) : Datas.lerPeriodo(h.ate().strip(), prefixo + "ate");
        if (ate.isBefore(de) || ate.isAfter(YearMonth.from(fim))) {
            throw ErroTipado.validacao(prefixo + "ate", "O último mês vai de " + de + " a " + YearMonth.from(fim) + ", o fim do horizonte.");
        }
        int meses = Math.toIntExact(de.until(ate, java.time.temporal.ChronoUnit.MONTHS) + 1);
        long[] plano = CalculoMeta.planoDeAportes(progressos.getOrDefault(meta.id(), 0L), meta.alvoCentavos(), mensal.centavos(), meses);
        List<Fluxo> fluxos = new ArrayList<>();
        List<ProjecaoVista.ItemHipotese> itens = new ArrayList<>();
        for (int i = 0; i < meses; i++) {
            if (plano[i] == 0) {
                continue;
            }
            YearMonth mes = de.plusMonths(i);
            LocalDate data = mes.equals(YearMonth.from(hoje)) ? hoje : mes.atDay(1);
            fluxos.add(new Fluxo(Coluna.APORTE, data, plano[i], meta.id()));
            itens.add(new ProjecaoVista.ItemHipotese(data.toString(), Dinheiro.deCentavos(plano[i], moeda).texto(), "Meta " + meta.nome()));
        }
        String resumo = "Aporte de " + ResumoOperacao.dinheiro(mensal.texto(), moeda.getCurrencyCode()) + " por mês na meta " + meta.nome()
                + ", de " + de + " a " + ate + (fluxos.size() < meses ? ", parando quando ela chega no alvo" : "");
        return new Resolvida(new CalculoProjecao.Fluxos(fluxos), new ProjecaoVista.HipoteseVista("aporte_meta", resumo, itens));
    }

    private static YearMonth mesNoHorizonte(String texto, YearMonth padrao, LocalDate hoje, LocalDate fim, String prefixo) {
        YearMonth de = Argumentos.vazio(texto) ? padrao : Datas.lerPeriodo(texto.strip(), prefixo + "de");
        if (de.isBefore(YearMonth.from(hoje)) || de.isAfter(YearMonth.from(fim))) {
            throw ErroTipado.validacao(prefixo + "de", "O mês vai de " + YearMonth.from(hoje) + " a " + YearMonth.from(fim) + ", o horizonte.");
        }
        return de;
    }

    /** O cartão de cada lançamento pago no crédito nos meses de referência, pelo meio de pagamento dele. */
    private Map<UUID, UUID> cartaoDoLancamento(UUID dono, Periodo periodo, Set<UUID> cartoesDoDono) {
        Map<UUID, UUID> liquidacao = estrutura.meios(dono).stream().collect(Collectors.toMap(MeioPagamento::id, MeioPagamento::contaLiquidacao));
        Map<UUID, UUID> porLancamento = new HashMap<>();
        for (FatoPeriodo f : lancamentos.fatosDoPeriodo(dono, periodo.de(), periodo.ate(), null)) {
            UUID conta = f.meio() == null ? null : liquidacao.get(f.meio());
            if (conta != null && cartoesDoDono.contains(conta)) {
                porLancamento.put(f.lancamento(), conta);
            }
        }
        return porLancamento;
    }

    /** O vencimento da fatura de uma compra num dia, como {@code registrar_gasto} a escolheria; uma leitura por cartão. */
    private CalculoProjecao.Vencimentos vencimentos(Map<UUID, CartaoCredito> cartoesDoDono) {
        Map<UUID, Function<String, Optional<Fatura>>> gravadas = new HashMap<>();
        Map<UUID, Map<LocalDate, LocalDate>> memoria = new HashMap<>();
        return (cartao, compra) -> memoria.computeIfAbsent(cartao, k -> new HashMap<>()).computeIfAbsent(compra, d -> {
            CartaoCredito c = cartoesDoDono.get(cartao);
            Function<String, Optional<Fatura>> existentes = gravadas.computeIfAbsent(cartao, faturas::faturasGravadas);
            return CartaoCredito.vencimento(c.cicloDaCompra(d, existentes), existentes);
        });
    }

    private static ProjecaoVista.CenarioVisto cenario(String rotulo, List<ProjecaoVista.HipoteseVista> hipoteses, CalculoProjecao.Resultado r,
            CalculoProjecao.Diferenca d, Function<Long, String> dinheiro, Map<UUID, MetaVista> vistas) {
        Set<YearMonth> abaixo50 = Set.copyOf(r.abaixoPisoP50());
        Set<YearMonth> abaixo80 = Set.copyOf(r.abaixoPisoP80());
        Set<YearMonth> reserva50 = Set.copyOf(r.abaixoReservaP50());
        Set<YearMonth> reserva80 = Set.copyOf(r.abaixoReservaP80());
        List<ProjecaoVista.MesVisto> meses = r.meses().stream().map(m -> new ProjecaoVista.MesVisto(m.mes().toString(),
                dinheiro.apply(m.inicialP50()), dinheiro.apply(m.inicialP80()), dinheiro.apply(m.entradas()), dinheiro.apply(m.saidas()),
                dinheiro.apply(m.faturas()), dinheiro.apply(m.aportes()), m.variavelP50() == null ? null : dinheiro.apply(m.variavelP50()),
                m.variavelP80() == null ? null : dinheiro.apply(m.variavelP80()), dinheiro.apply(m.finalP50()), dinheiro.apply(m.finalP80()),
                abaixo50.contains(m.mes()), abaixo80.contains(m.mes()), dinheiro.apply(m.renda()), dinheiro.apply(m.parcelas()),
                m.comprometimento() == null ? null : m.comprometimento().toPlainString(), reserva50.contains(m.mes()), reserva80.contains(m.mes())))
                .toList();
        CalculoProjecao.Pico pico = r.pico();
        CalculoProjecao.PicoParcelas picoParcelas = r.picoParcelas();
        ProjecaoVista.Indicadores indicadores = new ProjecaoVista.Indicadores(
                new ProjecaoVista.Menor(dinheiro.apply(r.menorP50().centavos()), r.menorP50().mes().toString()),
                new ProjecaoVista.Menor(dinheiro.apply(r.menorP80().centavos()), r.menorP80().mes().toString()),
                r.abaixoPisoP50().stream().map(YearMonth::toString).toList(), r.abaixoPisoP80().stream().map(YearMonth::toString).toList(),
                pico == null ? null : new ProjecaoVista.Pico(pico.mes().toString(), dinheiro.apply(pico.faturas()), dinheiro.apply(pico.entradas()),
                        pico.percentual().toPlainString()), r.abaixoReservaP50().stream().map(YearMonth::toString).toList(),
                r.abaixoReservaP80().stream().map(YearMonth::toString).toList(), picoParcelas == null ? null
                        : new ProjecaoVista.PicoParcelas(picoParcelas.mes().toString(), dinheiro.apply(picoParcelas.parcelas()),
                                dinheiro.apply(picoParcelas.renda()), picoParcelas.percentual().toPlainString()),
                r.mesesSemRenda().stream().map(YearMonth::toString).toList());
        ProjecaoVista.DiferencaVista diferenca = d == null ? null : new ProjecaoVista.DiferencaVista(dinheiro.apply(d.finalP50()),
                dinheiro.apply(d.finalP80()), dinheiro.apply(d.menorP50()), dinheiro.apply(d.menorP80()), d.meses().stream()
                        .map(x -> new ProjecaoVista.DiferencaMesVista(x.mes().toString(), dinheiro.apply(x.finalP50()), dinheiro.apply(x.finalP80())))
                        .toList());
        List<ProjecaoVista.MetaProjetada> metasProjetadas = r.metas().stream().map(x -> {
            MetaVista v = vistas.get(x.meta());
            String situacao = x.mes() == null ? "Não alcança no horizonte" : x.noPrazo() == null ? "Sem data alvo"
                    : x.noPrazo() ? "No prazo" : "Depois da data alvo";
            return new ProjecaoVista.MetaProjetada(v.id(), v.url(), v.nome(), v.valorAlvo(), v.dataAlvo(), v.progresso(), dinheiro.apply(x.aportado()),
                    x.mes() == null ? null : x.mes().toString(), x.mes() != null, x.noPrazo(), situacao);
        }).toList();
        List<ProjecaoVista.Violacao> violacoes = r.violacoes().stream().map(v -> {
            MetaVista meta = v.meta() == null ? null : vistas.get(v.meta());
            Function<Long, String> opcional = c -> c == null ? null : dinheiro.apply(c);
            return new ProjecaoVista.Violacao(v.regra().codigo(), v.mes().toString(), opcional.apply(v.saldoFinalP80()), opcional.apply(v.limite()),
                    opcional.apply(v.parcelas()), opcional.apply(v.renda()), v.comprometimento() == null ? null : v.comprometimento().toPlainString(),
                    v.teto() == null ? null : v.teto().toPlainString(), meta == null ? null : meta.id(), meta == null ? null : meta.url(),
                    meta == null ? null : meta.nome(), opcional.apply(v.aporteNecessario()));
        }).toList();
        return new ProjecaoVista.CenarioVisto(rotulo, hipoteses, meses, indicadores, diferenca, metasProjetadas, violacoes);
    }
}
