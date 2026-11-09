package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.ItemDaFatura;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.Pagamento;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.ParcelaNaAbertura;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes.Totais;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.CicloFatura;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.DestinoParcela;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.Partida;
import tools.alicerce.financeiro.dominio.Partidas;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.Repartir;
import tools.alicerce.financeiro.dominio.SituacaoFatura;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** O que se faz com faturas: criar sob demanda, escolher a certa, mostrar e proteger as já pagas. */
@Component
class Faturas {

    private static final Clock RELOGIO = Clock.systemUTC();

    private final RepositorioCartoes cartoes;
    private final RepositorioEstrutura estrutura;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioDono donos;
    private final Enderecos enderecos;
    private final EstruturaBasica basica;

    Faturas(RepositorioCartoes cartoes, RepositorioEstrutura estrutura, RepositorioLancamentos lancamentos, RepositorioDono donos,
            Enderecos enderecos, EstruturaBasica basica) {
        this.cartoes = cartoes;
        this.estrutura = estrutura;
        this.lancamentos = lancamentos;
        this.donos = donos;
        this.enderecos = enderecos;
        this.basica = basica;
    }

    /**
     * Um gasto a transformar em movimentos.
     *
     * @param naAbertura a resposta do dono sobre a parcela da fatura com abertura; nula se ele não respondeu
     * @param substituido o lançamento que esta correção troca: o que ele pôs na abertura vai sair
     * @param campo o nome de {@code parcela_na_abertura} na entrada
     */
    record Compra(Conta liquidacao, LocalDate data, Dinheiro total, List<Partida> partidas, int parcelas, Procedencia procedencia,
            Boolean naAbertura, Lancamento substituido, String campo) {}

    /** Os movimentos de um gasto: no cartão, uma parcela por fatura; em outra conta, a saída na hora. Antes do marco, sem mexer na conta. */
    List<Movimento> movimentosDoGasto(OperacaoEmCurso operacao, Compra c) {
        boolean exogeno = c.procedencia() == Procedencia.EXOGENO;
        if (c.liquidacao().tipo() != TipoConta.CARTAO_CREDITO) {
            UUID conta = exogeno ? basica.contaSaldoDeAbertura(operacao, c.liquidacao().moeda()) : c.liquidacao().id();
            return Partidas.gasto(c.total(), conta, c.partidas());
        }
        CartaoCredito cartao = cartoes.cartao(c.liquidacao().id()).orElseThrow();
        if (exogeno) {
            return emAndamento(operacao, cartao, c);
        }
        List<UUID> ids = new ArrayList<>();
        for (CicloFatura ciclo : cartao.ciclosDaCompra(c.data(), c.parcelas(), p -> cartoes.fatura(cartao.conta(), p))) {
            ids.add(garantir(operacao, cartao, ciclo).id());
        }
        return Partidas.gastoNoCredito(c.total(), cartao.conta(), c.partidas(), ids);
    }

    /**
     * Uma compra anterior ao marco. A fatura da abertura é o corte: o que vence antes dela já foi pago e
     * não ganha fatura; o que vence depois é dívida de verdade; o que cai nela depende da resposta do dono,
     * porque só ele sabe o que a abertura contém.
     */
    private List<Movimento> emAndamento(OperacaoEmCurso operacao, CartaoCredito cartao, Compra c) {
        Optional<Fatura> daAbertura = cartoes.faturaDaAbertura(cartao.conta()).flatMap(id -> cartoes.faturaPorId(operacao.dono(), id));
        String corte = daAbertura.map(Fatura::periodo).orElseGet(() -> cartao.proximoAVencer(estrutura.marco(cartao.conta()).orElseThrow()).periodo());
        UUID tecnica = basica.contaSaldoDeAbertura(operacao, c.liquidacao().moeda());
        long[] valores = Repartir.parcelas(c.total().centavos(), c.parcelas());
        List<DestinoParcela> destinos = new ArrayList<>();
        CicloFatura ciclo = cartao.cicloDaCompra(c.data());
        for (int i = 0; i < c.parcelas(); i++) {
            int comparacao = ciclo.periodo().compareTo(corte);
            if (comparacao < 0) {
                destinos.add(DestinoParcela.anterior(tecnica));
            } else if (comparacao == 0 && daAbertura.isPresent()) {
                Fatura fatura = daAbertura.get();
                exigirResposta(c, fatura, i + 1);
                if (c.naAbertura()) {
                    cabeNaAbertura(fatura, valores[i], c);
                    destinos.add(DestinoParcela.naAbertura(tecnica, fatura.id()));
                } else {
                    destinos.add(DestinoParcela.noCartao(cartao.conta(), fatura.id()));
                }
            } else {
                destinos.add(DestinoParcela.noCartao(cartao.conta(), garantir(operacao, cartao, ciclo).id()));
            }
            ciclo = cartao.seguinte(ciclo);
        }
        return Partidas.gastoEmParcelas(c.total(), cartao.conta(), c.partidas(), destinos);
    }

    private static void exigirResposta(Compra c, Fatura fatura, int parcela) {
        if (c.naAbertura() == null) {
            String qual = c.parcelas() == 1 ? "Essa compra" : "A parcela " + parcela + "/" + c.parcelas();
            throw ErroTipado.camposFaltando(List.of(c.campo()), qual + " cai na fatura " + fatura.periodo()
                    + ", que tem saldo de abertura. Pergunte ao dono se ela já está dentro desse saldo, e não responda por ele.");
        }
    }

    private void cabeNaAbertura(Fatura fatura, long centavos, Compra c) {
        Currency moeda = c.total().moeda();
        long abertura = aberturaDaFatura(fatura.id());
        long detalhado = detalhadoNaFatura(fatura.id()) - (c.substituido() == null ? 0 : naAbertura(c.substituido(), fatura.id()));
        if (detalhado + centavos > abertura) {
            throw ErroTipado.validacao(c.campo(), "A parcela de " + Dinheiro.deCentavos(centavos, moeda).texto() + " não cabe na abertura da fatura "
                    + fatura.periodo() + ": ela é de " + Dinheiro.deCentavos(abertura, moeda).texto() + ", e "
                    + Dinheiro.deCentavos(detalhado, moeda).texto() + " já estão detalhados. Confira com o dono.");
        }
    }

    private long aberturaDaFatura(UUID fatura) {
        return cartoes.itens(fatura).stream().filter(i -> i.tipo() == TipoLancamento.SALDO_ABERTURA).mapToLong(ItemDaFatura::centavos).sum();
    }

    private long detalhadoNaFatura(UUID fatura) {
        return cartoes.parcelasNaAbertura(fatura).stream().mapToLong(ParcelaNaAbertura::centavos).sum();
    }

    private static long naAbertura(Lancamento l, UUID fatura) {
        return l.movimentos().stream().filter(m -> fatura.equals(m.faturaAbertura())).mapToLong(m -> -m.valor().centavos()).sum();
    }

    /** O que uma correção mantém quando o dono não responde de novo. Nulo se a pergunta é nova. */
    Boolean respostaAnterior(Lancamento l, UUID cartao) {
        if (l.movimentos().stream().anyMatch(m -> m.faturaAbertura() != null)) {
            return Boolean.TRUE;
        }
        Optional<UUID> fatura = cartoes.faturaDaAbertura(cartao);
        return fatura.isPresent() && l.movimentos().stream().anyMatch(m -> fatura.get().equals(m.fatura())) ? Boolean.FALSE : null;
    }

    /** {@code parcela_atual} é só conferência: a numeração sai da data e dos dias do cartão. */
    void conferirParcelaAtual(UUID dono, Conta liquidacao, LocalDate data, int parcelas, Integer informada) {
        if (informada == null) {
            return;
        }
        if (liquidacao.tipo() != TipoConta.CARTAO_CREDITO) {
            throw ErroTipado.validacao("parcela_atual", "Parcela atual só vale no crédito.");
        }
        Dono dados = donos.buscar(dono).orElseThrow();
        int calculada = cartoes.cartao(liquidacao.id()).orElseThrow().parcelaEm(data, Datas.hoje(RELOGIO, dados.fuso()));
        if (calculada != informada) {
            String conta = calculada > parcelas ? "todas as " + parcelas + " parcelas já venceram" : "a parcela da próxima fatura é " + calculada + "/" + parcelas;
            throw ErroTipado.validacao("parcela_atual", "Pela data da compra e pelos dias do cartão, " + conta + ", e não " + informada
                    + ". Confira a data e o número de parcelas com o dono.");
        }
    }

    /** A fatura de um saldo de abertura: a próxima a vencer, mesmo já fechada. */
    Fatura proximaAVencer(OperacaoEmCurso operacao, UUID conta, LocalDate data) {
        CartaoCredito cartao = cartoes.cartao(conta).orElseThrow();
        return garantir(operacao, cartao, cartao.proximoAVencer(data));
    }

    /** O ciclo da compra. Depois de mudar os dias do cartão, uma fatura já criada pode ter fechado antes da data. */
    private CicloFatura cicloDaCompra(CartaoCredito cartao, LocalDate data) {
        return cartao.cicloDaCompra(data, p -> cartoes.fatura(cartao.conta(), p));
    }

    /**
     * As faturas já gravadas de um cartão, por período, para simular compras sem gravar nada: a mesma escolha de
     * ciclo de {@link #movimentosDoGasto}, sem uma consulta por dia.
     */
    java.util.function.Function<String, Optional<Fatura>> faturasGravadas(UUID cartao) {
        Map<String, Fatura> porPeriodo = cartoes.faturas(cartao).stream().collect(Collectors.toMap(Fatura::periodo, f -> f, (a, b) -> a));
        return p -> Optional.ofNullable(porPeriodo.get(p));
    }

    private Fatura garantir(OperacaoEmCurso operacao, CartaoCredito cartao, CicloFatura ciclo) {
        Fatura nova = new Fatura(UUID.randomUUID(), cartao.conta(), ciclo.periodo(), ciclo.fechaEm(), ciclo.venceEm());
        if (cartoes.inserirFatura(operacao, nova)) {
            operacao.faturaCriada(nova.id());
            operacao.referenciar(TipoReferencia.FATURA, nova.id());
            return nova;
        }
        return cartoes.fatura(cartao.conta(), ciclo.periodo()).orElseThrow();
    }

    /** A fatura que um pagamento quita: a informada ou a fechada mais antiga com saldo. */
    Fatura aPagar(UUID dono, Conta cartao, String texto, LocalDate data) {
        if (!Argumentos.vazio(texto)) {
            return informada(dono, cartao, texto.strip(), "fatura");
        }
        Map<UUID, Totais> totais = cartoes.totais(cartao.id());
        return cartoes.faturas(cartao.id()).stream()
                .filter(f -> f.fechaEm().isBefore(data))
                .filter(f -> {
                    Totais t = totais.getOrDefault(f.id(), new Totais(0, 0));
                    return t.comprado() > t.pago();
                })
                .findFirst().orElseThrow(() -> ErroTipado.validacao("fatura",
                        "Não há fatura fechada a pagar em '" + cartao.nome() + "'. Para adiantar, informe a fatura."));
    }

    /** A fatura por id curto, URL ou período. */
    Fatura informada(UUID dono, Conta cartao, String texto, String campo) {
        Optional<ReferenciaCurta> curta = ReferenciaCurta.ler(texto, TipoReferencia.MODULO);
        Optional<Fatura> achada = curta.isPresent()
                ? curta.flatMap(r -> estrutura.entidadeDaReferencia(dono, r.id())).flatMap(id -> cartoes.faturaPorId(dono, id))
                        .filter(f -> f.cartao().equals(cartao.id()))
                : cartoes.fatura(cartao.id(), Datas.lerPeriodo(texto, campo).toString());
        return achada.orElseThrow(() -> ErroTipado.de(CodigoErro.NAO_ENCONTRADO, campo,
                "Nenhuma fatura de '" + cartao.nome() + "' corresponde a '" + texto + "'."));
    }

    /** Quanto falta pagar. */
    Dinheiro restante(Fatura fatura, Currency moeda) {
        Totais t = cartoes.totais(fatura.cartao()).getOrDefault(fatura.id(), new Totais(0, 0));
        return Dinheiro.deCentavos(Math.max(t.comprado() - t.pago(), 0), moeda);
    }

    /** A fatura inteira, com itens e pagamentos. */
    FaturaVista ver(UUID dono, Fatura fatura) {
        Dono dados = donos.buscar(dono).orElseThrow();
        Currency moeda = dados.moeda();
        List<ItemDaFatura> itens = cartoes.itens(fatura.id());
        List<Pagamento> pagamentos = cartoes.pagamentos(List.of(fatura.id()));
        long comprado = itens.stream().mapToLong(ItemDaFatura::centavos).sum();
        long pago = pagamentos.stream().mapToLong(Pagamento::centavos).sum();
        return vista(dono, fatura.cartao(), referencia(dono, fatura.id()), fatura.periodo(), fatura.fechaEm(), fatura.venceEm(),
                comprado, pago, Datas.hoje(RELOGIO, dados.fuso()), moeda, itens.stream().map(i -> item(i, moeda)).toList(),
                pagamentos.stream().map(p -> pagamento(p, moeda)).toList(), abertura(fatura.id(), itens, moeda));
    }

    /** Quanto da abertura já foi detalhado em parcelas; nulo em fatura sem abertura. */
    private FaturaVista.AberturaVista abertura(UUID fatura, List<ItemDaFatura> itens, Currency moeda) {
        long abertura = itens.stream().filter(i -> i.tipo() == TipoLancamento.SALDO_ABERTURA).mapToLong(ItemDaFatura::centavos).sum();
        if (abertura == 0) {
            return null;
        }
        List<ParcelaNaAbertura> parcelas = cartoes.parcelasNaAbertura(fatura);
        long detalhado = parcelas.stream().mapToLong(ParcelaNaAbertura::centavos).sum();
        return new FaturaVista.AberturaVista(Dinheiro.deCentavos(abertura, moeda).texto(), Dinheiro.deCentavos(detalhado, moeda).texto(),
                Dinheiro.deCentavos(abertura - detalhado, moeda).texto(),
                parcelas.stream().map(p -> new FaturaVista.ParcelaDetalhada(p.codigo(), url(p.codigo()), p.descricao(), p.parcela(),
                        Dinheiro.deCentavos(p.centavos(), moeda).texto())).toList());
    }

    /** A fatura em que uma compra de hoje entra, mesmo que ainda não exista. */
    FaturaVista atual(UUID dono, CartaoCredito cartao) {
        Dono dados = donos.buscar(dono).orElseThrow();
        LocalDate hoje = Datas.hoje(RELOGIO, dados.fuso());
        CicloFatura ciclo = cicloDaCompra(cartao, hoje);
        return cartoes.fatura(cartao.conta(), ciclo.periodo()).map(f -> ver(dono, f))
                .orElseGet(() -> vista(dono, cartao.conta(), null, ciclo.periodo(), ciclo.fechaEm(), ciclo.venceEm(), 0, 0, hoje,
                        dados.moeda(), List.of(), List.of(), null));
    }

    private FaturaVista vista(UUID dono, UUID cartao, ReferenciaCurta referencia, String periodo, LocalDate fechaEm, LocalDate venceEm,
            long comprado, long pago, LocalDate hoje, Currency moeda, List<FaturaVista.ItemVisto> itens,
            List<FaturaVista.PagamentoVisto> pagamentos, FaturaVista.AberturaVista abertura) {
        Dinheiro total = Dinheiro.deCentavos(comprado, moeda);
        Dinheiro pagoTotal = Dinheiro.deCentavos(pago, moeda);
        String nome = estrutura.contas(dono).stream().filter(c -> c.id().equals(cartao)).map(Conta::nome).findFirst().orElseThrow();
        return new FaturaVista(referencia == null ? null : referencia.id(), referencia == null ? null : enderecos.url(referencia), nome,
                periodo, fechaEm.toString(), venceEm.toString(), SituacaoFatura.de(hoje, fechaEm, total, pagoTotal).codigo(), total.texto(),
                pagoTotal.texto(), Dinheiro.deCentavos(Math.max(comprado - pago, 0), moeda).texto(), moeda.getCurrencyCode(), itens, pagamentos, abertura);
    }

    private ReferenciaCurta referencia(UUID dono, UUID fatura) {
        return estrutura.referenciaDe(dono, TipoReferencia.FATURA, fatura).flatMap(c -> ReferenciaCurta.ler(c, TipoReferencia.MODULO))
                .orElseThrow();
    }

    private FaturaVista.ItemVisto item(ItemDaFatura i, Currency moeda) {
        return new FaturaVista.ItemVisto(i.codigo(), url(i.codigo()), i.data().toString(), i.descricao(),
                i.tipo().name().toLowerCase(java.util.Locale.ROOT), Dinheiro.deCentavos(i.centavos(), moeda).texto(), i.parcela());
    }

    private FaturaVista.PagamentoVisto pagamento(Pagamento p, Currency moeda) {
        return new FaturaVista.PagamentoVisto(p.codigo(), url(p.codigo()), p.data().toString(), Dinheiro.deCentavos(p.centavos(), moeda).texto());
    }

    private String url(String codigo) {
        return ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
    }

    /** Como a compra ficou no cartão; nulo se não passou por cartão. */
    CreditoVisto credito(UUID dono, Lancamento l, Currency moeda) {
        List<Movimento> noCartao = l.movimentos().stream().filter(m -> m.fatura() != null).toList();
        UUID conta = noCartao.isEmpty() ? l.contaHistorica() : noCartao.get(0).conta();
        Optional<CartaoCredito> cartao = conta == null ? Optional.empty() : cartoes.cartao(conta);
        if (cartao.isEmpty()) {
            return null;
        }
        Map<UUID, Long> porFatura = new LinkedHashMap<>();
        Map<UUID, String> parcelas = new LinkedHashMap<>();
        for (Movimento m : noCartao) {
            porFatura.merge(m.fatura(), -m.valor().centavos(), Long::sum);
            parcelas.put(m.fatura(), m.parcelaTexto());
        }
        List<CreditoVisto.ParcelaVista> vistas = new ArrayList<>();
        porFatura.forEach((id, centavos) -> {
            Fatura f = cartoes.faturaPorId(dono, id).orElseThrow();
            ReferenciaCurta referencia = referencia(dono, id);
            vistas.add(new CreditoVisto.ParcelaVista(referencia.id(), enderecos.url(referencia), f.periodo(), f.venceEm().toString(),
                    parcelas.get(id), Dinheiro.deCentavos(centavos, moeda).texto()));
        });
        vistas.sort(java.util.Comparator.comparing(CreditoVisto.ParcelaVista::venceEm));
        return new CreditoVisto(l.parcelas(), l.precoAVista() == null ? null : l.precoAVista().texto(), vistas,
                disponivel(conta, moeda), emAndamento(dono, l, cartao.get(), noCartao, moeda));
    }

    /** Como as parcelas de uma compra anterior ao marco se dividem; nulo nas demais. */
    private CreditoVisto.HistoricoCompra emAndamento(UUID dono, Lancamento l, CartaoCredito cartao, List<Movimento> noCartao, Currency moeda) {
        if (l.procedencia() != Procedencia.EXOGENO) {
            return null;
        }
        List<Movimento> naAbertura = l.movimentos().stream().filter(m -> m.faturaAbertura() != null).toList();
        long emAberto = noCartao.stream().mapToLong(m -> -m.valor().centavos()).sum();
        long dentro = naAbertura.stream().mapToLong(m -> -m.valor().centavos()).sum();
        int abertas = (int) noCartao.stream().map(Movimento::fatura).distinct().count();
        int dentroDaAbertura = (int) naAbertura.stream().map(Movimento::faturaAbertura).distinct().count();
        int atual = cartao.parcelaEm(l.data(), Datas.hoje(RELOGIO, donos.buscar(dono).orElseThrow().fuso()));
        return new CreditoVisto.HistoricoCompra(atual > l.parcelas() ? null : Integer.valueOf(atual), l.parcelas() - abertas - dentroDaAbertura,
                Dinheiro.deCentavos(l.total().centavos() - emAberto - dentro, moeda).texto(), Dinheiro.deCentavos(dentro, moeda).texto(),
                Dinheiro.deCentavos(emAberto, moeda).texto());
    }

    /** Limite menos o que se deve no cartão; nulo sem limite informado. */
    String disponivel(UUID conta, Currency moeda) {
        return cartoes.cartao(conta).map(CartaoCredito::limiteCentavos).filter(Objects::nonNull)
                .map(limite -> Dinheiro.deCentavos(limite + lancamentos.saldoCentavos(conta), moeda).texto()).orElse(null);
    }

    /** Como as faturas ficaram, sem itens, da que vence primeiro à última. */
    List<FaturaVista> afetadas(UUID dono, Collection<UUID> ids) {
        return ids.stream().distinct().map(id -> ver(dono, cartoes.faturaPorId(dono, id).orElseThrow()).semDetalhes())
                .sorted(java.util.Comparator.comparing(FaturaVista::venceEm)).toList();
    }

    /**
     * Mudar o que uma fatura cobra só é recusado se sobrar pagamento: o pagamento é um fato e fica intacto, e
     * crédito a favor do dono ainda não existe. {@code novo} nulo é anular. Numa abertura, o novo valor
     * também precisa cobrir o que já foi detalhado nela.
     */
    void semSobrepagar(UUID dono, Lancamento original, Lancamento novo) {
        if (original.tipo() == TipoLancamento.PAGAMENTO_FATURA) {
            return;
        }
        Map<UUID, Long> antes = cobrado(original);
        Map<UUID, Long> depois = novo == null ? Map.of() : cobrado(novo);
        Set<UUID> ids = new LinkedHashSet<>(antes.keySet());
        ids.addAll(depois.keySet());
        Currency moeda = donos.buscar(dono).orElseThrow().moeda();
        for (UUID id : ids) {
            long tirado = antes.getOrDefault(id, 0L);
            long posto = depois.getOrDefault(id, 0L);
            if (tirado != posto) {
                conferirNovoTotal(dono, cartoes.faturaPorId(dono, id).orElseThrow(), posto - tirado, moeda);
                if (original.tipo() == TipoLancamento.SALDO_ABERTURA) {
                    conferirDetalhe(id, posto, moeda);
                }
            }
        }
    }

    private void conferirNovoTotal(UUID dono, Fatura f, long diferenca, Currency moeda) {
        Totais t = cartoes.totais(f.cartao()).getOrDefault(f.id(), new Totais(0, 0));
        long novoTotal = t.comprado() + diferenca;
        if (t.pago() <= novoTotal) {
            return;
        }
        List<ErroTipado.Candidato> candidatos = cartoes.pagamentos(List.of(f.id())).stream()
                .map(p -> new ErroTipado.Candidato(p.codigo(), p.descricao(), url(p.codigo()))).toList();
        throw ErroTipado.referencia(CodigoErro.VIOLACAO_INVARIANTE, "lancamento", "Isso deixaria pagamento sobrando na fatura " + f.periodo()
                + ": o total iria de " + texto(t.comprado(), moeda) + " para " + texto(novoTotal, moeda) + ", com " + texto(t.pago(), moeda)
                + " pagos, " + texto(t.pago() - novoTotal, moeda) + " a mais.", candidatos,
                "Mostre esses números ao dono. O pagamento fica como está, e crédito a favor ainda não existe: use um valor que caiba. "
                        + "Só anule um pagamento se ele mesmo estiver errado.");
    }

    private void conferirDetalhe(UUID fatura, long novaAbertura, Currency moeda) {
        long detalhado = detalhadoNaFatura(fatura);
        if (detalhado > novaAbertura) {
            throw ErroTipado.referencia(CodigoErro.VIOLACAO_INVARIANTE, "lancamento", "A abertura iria para " + texto(novaAbertura, moeda)
                    + ", abaixo dos " + texto(detalhado, moeda) + " já detalhados em parcelas.",
                    cartoes.parcelasNaAbertura(fatura).stream().map(p -> new ErroTipado.Candidato(p.codigo(), p.descricao(), url(p.codigo()))).toList(),
                    "Mostre ao dono as compras ligadas à abertura. Para seguir, corrija ou anule essas compras antes.");
        }
    }

    private static String texto(long centavos, Currency moeda) {
        return Dinheiro.deCentavos(centavos, moeda).texto();
    }

    private static Map<UUID, Long> cobrado(Lancamento l) {
        return l.movimentos().stream().filter(m -> m.fatura() != null)
                .collect(Collectors.toMap(Movimento::fatura, m -> -m.valor().centavos(), Long::sum));
    }

    /** As operações que impedem um desfazer de deixar estas faturas assim: pagamento sobrando ou abertura menor que o detalhe. */
    Set<UUID> impedimentos(UUID dono, Collection<UUID> faturas) {
        Set<UUID> operacoes = new LinkedHashSet<>();
        for (UUID id : faturas) {
            Fatura f = cartoes.faturaPorId(dono, id).orElseThrow();
            Totais t = cartoes.totais(f.cartao()).getOrDefault(id, new Totais(0, 0));
            if (t.pago() > t.comprado()) {
                cartoes.pagamentos(List.of(id)).forEach(p -> operacoes.add(p.operacao()));
            }
            if (detalhadoNaFatura(id) > aberturaDaFatura(id)) {
                cartoes.parcelasNaAbertura(id).forEach(p -> operacoes.add(lancamentos.operacaoDe(p.lancamento())));
            }
        }
        return operacoes;
    }
}
