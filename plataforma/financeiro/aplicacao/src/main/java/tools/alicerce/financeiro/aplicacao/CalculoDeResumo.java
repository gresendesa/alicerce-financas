package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.ContaConvertida;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.DestaqueVisto;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.HistoricoVisto;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.LinhaVista;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.PeriodoAnterior;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.QuebraVista;
import tools.alicerce.financeiro.aplicacao.ResumoVisto.SaldosVistos;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.CalculoCaixa;
import tools.alicerce.financeiro.dominio.CalculoConversao;
import tools.alicerce.financeiro.dominio.CalculoResumo;
import tools.alicerce.financeiro.dominio.CalculoResumo.Grupo;
import tools.alicerce.financeiro.dominio.CalculoResumo.Totais;
import tools.alicerce.financeiro.dominio.CalculoResumo.Variacao;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.FatoPeriodo;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.Quebra;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Monta o resumo de um período a partir de parâmetros já resolvidos. Só lê: quem guarda o retrato é a capacidade. */
@Component
class CalculoDeResumo {

    static final String COMPETENCIA = "competencia";
    static final String CAIXA = "caixa";

    private static final int MAIORES = 5;
    static final String NOTA_CAIXA = "Na visão caixa, compra no crédito conta parcela a parcela, no vencimento da fatura, "
            + "paga ou não. Pagamento de fatura e transferência não contam, para a mesma compra não contar duas vezes.";

    private static final String NOTA_HISTORICO = "Já está dentro de entradas e saidas: nunca some. É o que aconteceu antes do marco da conta: "
            + "conta no gasto do período, mas esse dinheiro não passou pelas contas agora.";

    private static final String NOTA_SALDOS = "Saldos no fim do período, cada conta na sua moeda. Cada conversão usa a última cotação registrada pelo dono até essa data, "
            + "com a data ao lado; não é a cotação do dia, e ganho ou perda cambial não é calculado. total já inclui saldo_moeda_dono e saldo_convertido: nunca some.";

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final Enderecos enderecos;
    private final RepositorioCartoes cartoes;
    private final Conferencias conferencias;
    private final RepositorioDono donos;

    CalculoDeResumo(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, Enderecos enderecos, RepositorioCartoes cartoes,
            Conferencias conferencias, RepositorioDono donos) {
        this.conferencias = conferencias;
        this.donos = donos;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.enderecos = enderecos;
        this.cartoes = cartoes;
    }

    private record Nomes(Map<UUID, Conta> contas, Map<UUID, String> codigos, Map<UUID, String> ambitos, Map<UUID, String> meios) {}

    ResumoVisto calcular(UUID dono, Currency moeda, ParametrosResumo p, OffsetDateTime agora) {
        Periodo periodo = new Periodo(LocalDate.parse(p.de()), LocalDate.parse(p.ate()));
        UUID ambito = p.ambitoId() == null ? null : UUID.fromString(p.ambitoId());
        List<FatoPeriodo> fatos = fatos(dono, periodo, ambito, p.visao());
        Totais totais = CalculoResumo.totais(fatos, moeda);
        Nomes nomes = nomes(dono);
        List<QuebraVista> quebras = p.agruparPor().stream()
                .map(codigo -> quebra(fatos, Quebra.deCodigo(codigo).orElseThrow(), p, nomes, moeda)).toList();
        long quantidade = fatos.stream().map(FatoPeriodo::lancamento).distinct().count();
        return new ResumoVisto(agora.toString(), p.de(), p.ate(), p.visao(), CAIXA.equals(p.visao()) ? NOTA_CAIXA : null, moeda.getCurrencyCode(), p.ambito(),
                totais.entradas().texto(), totais.saidas().texto(), totais.saldo().texto(), (int) quantidade, quebras,
                destaques(fatos, TipoLancamento.GANHO, moeda), destaques(fatos, TipoLancamento.GASTO, moeda),
                p.compararAnterior() ? anterior(dono, periodo.anterior(), ambito, p.visao(), totais, moeda) : null,
                historico(fatos, moeda), avisoHistorico(dono, nomes, p.compararAnterior() ? periodo.anterior().de() : periodo.de()),
                saldos(dono, moeda, nomes, periodo.ate()), ambito == null ? ajustes(dono, periodo, moeda) : null,
                conferencias.vencidas(dono, LocalDate.ofInstant(agora.toInstant(), donos.buscar(dono).orElseThrow().fuso())));
    }

    private static final String NOTA_AJUSTES = "Diferenças de conferência aceitas pelo dono, contra Ajuste não identificado. Ficam fora de entradas, "
            + "saidas e saldo, numa linha própria, para a diferença não sumir; positivo é dinheiro que apareceu sem registro.";

    /** Só aparece se houve ajuste. Com filtro de âmbito, fica fora: ajuste não tem âmbito. */
    private ResumoVisto.AjustesVistos ajustes(UUID dono, Periodo periodo, Currency moeda) {
        List<RepositorioLancamentos.Ajuste> ajustes = lancamentos.ajustesDoPeriodo(dono, periodo.de(), periodo.ate());
        if (ajustes.isEmpty()) {
            return null;
        }
        long soma = ajustes.stream().filter(a -> a.moeda().equals(moeda.getCurrencyCode())).mapToLong(RepositorioLancamentos.Ajuste::centavos).sum();
        List<DestaqueVisto> linhas = ajustes.stream().map(a -> new DestaqueVisto(a.codigo(),
                a.codigo() == null ? null : ReferenciaCurta.ler(a.codigo(), TipoReferencia.MODULO).map(enderecos::url).orElse(null),
                a.data().toString(), a.descricao(), Dinheiro.deCentavos(a.centavos(), Currency.getInstance(a.moeda())).texto(), null)).toList();
        return new ResumoVisto.AjustesVistos(Dinheiro.deCentavos(soma, moeda).texto(), ajustes.size(), linhas, NOTA_AJUSTES);
    }

    /** Só aparece se há conta em outra moeda: sem nenhuma, o resumo fica como sempre foi. */
    private SaldosVistos saldos(UUID dono, Currency moeda, Nomes nomes, LocalDate ate) {
        List<Conta> contas = nomes.contas().values().stream().filter(c -> c.tipo().temSaldo() && !c.arquivada())
                .sorted(Comparator.comparing(Conta::nome)).toList();
        if (contas.stream().allMatch(c -> c.moeda().equals(moeda.getCurrencyCode()))) {
            return null;
        }
        Map<UUID, Long> centavos = lancamentos.saldosCentavosAte(dono, ate);
        List<CalculoConversao.SaldoConta> saldos = contas.stream().map(c -> new CalculoConversao.SaldoConta(c.id(),
                Dinheiro.deCentavos(centavos.getOrDefault(c.id(), 0L), Currency.getInstance(c.moeda())))).toList();
        CalculoConversao.Resultado r = CalculoConversao.converter(saldos, lancamentos.cotacoes(dono, ate), moeda);
        List<ContaConvertida> linhas = r.linhas().stream().map(l -> contaConvertida(l, nomes, moeda)).toList();
        return new SaldosVistos(ate.toString(), moeda.getCurrencyCode(), r.naMoedaDoDono().texto(), r.convertido().texto(), r.total().texto(),
                linhas, NOTA_SALDOS, avisoSemCotacao(r, nomes));
    }

    private static ContaConvertida contaConvertida(CalculoConversao.Linha l, Nomes nomes, Currency moeda) {
        CalculoConversao.Cotacao c = l.cotacao();
        return new ContaConvertida(nomes.codigos().get(l.conta()), nomes.contas().get(l.conta()).nome(), l.saldo().moeda().getCurrencyCode(), l.saldo().texto(),
                c == null ? null : c.valor().stripTrailingZeros().toPlainString(), c == null ? null : c.data().toString(), c == null ? null : c.lancamento(),
                l.convertido() == null ? null : l.convertido().texto(), l.convertido() == null ? null : moeda.getCurrencyCode());
    }

    private static String avisoSemCotacao(CalculoConversao.Resultado r, Nomes nomes) {
        if (r.foraDoTotal().isEmpty()) {
            return null;
        }
        String contas = r.foraDoTotal().stream().map(l -> nomes.contas().get(l.conta()).nome() + " (" + l.saldo() + ")").collect(Collectors.joining(", "));
        return "Fora do total, por não haver nenhuma cotação registrada até o fim do período: " + contas
                + ". Avise o dono e nunca some esses valores ao total. Uma transferência com cotação registra uma.";
    }

    private static HistoricoVisto historico(List<FatoPeriodo> fatos, Currency moeda) {
        long quantidade = fatos.stream().filter(FatoPeriodo::exogeno).map(FatoPeriodo::lancamento).distinct().count();
        if (quantidade == 0) {
            return null;
        }
        Totais exogenos = CalculoResumo.exogenos(fatos, moeda);
        return new HistoricoVisto(exogenos.entradas().texto(), exogenos.saidas().texto(), (int) quantidade, NOTA_HISTORICO);
    }

    /** Antes do marco só existe o que foi lançado como histórico: o mês parece completo, e não está. */
    private String avisoHistorico(UUID dono, Nomes nomes, LocalDate desde) {
        String contas = estrutura.marcos(dono).entrySet().stream().filter(m -> m.getValue().isAfter(desde))
                .filter(m -> !nomes.contas().get(m.getKey()).arquivada())
                .sorted(Map.Entry.comparingByValue())
                .map(m -> nomes.contas().get(m.getKey()).nome() + " desde " + m.getValue()).collect(Collectors.joining(", "));
        return contas.isEmpty() ? null : "Histórico parcial desde " + desde + ": as contas só estão cobertas a partir do marco (" + contas
                + "). Antes dele existe só o que foi lançado como histórico. Avise o dono e não trate como mês completo, nem nas comparações.";
    }

    /** Em competência, tudo pela data da compra. Em caixa, o crédito sai da lista e volta pelo vencimento. */
    private List<FatoPeriodo> fatos(UUID dono, Periodo periodo, UUID ambito, String visao) {
        if (!CAIXA.equals(visao)) {
            return lancamentos.fatosDoPeriodo(dono, periodo.de(), periodo.ate(), ambito);
        }
        List<FatoPeriodo> fatos = new ArrayList<>(lancamentos.fatosForaDoCredito(dono, periodo.de(), periodo.ate(), ambito));
        cartoes.comprasComVencimento(dono, periodo.de(), periodo.ate())
                .forEach(compra -> fatos.addAll(CalculoCaixa.fatos(compra, periodo, ambito)));
        return fatos;
    }

    private Nomes nomes(UUID dono) {
        return new Nomes(estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Function.identity())),
                estrutura.referenciasDeConta(dono),
                estrutura.ambitos(dono).stream().collect(Collectors.toMap(Ambito::id, Ambito::nome)),
                estrutura.meios(dono).stream().collect(Collectors.toMap(MeioPagamento::id, MeioPagamento::nome)));
    }

    private static QuebraVista quebra(List<FatoPeriodo> fatos, Quebra quebra, ParametrosResumo p, Nomes nomes, Currency moeda) {
        Function<FatoPeriodo, Grupo> grupo = switch (quebra) {
            case CATEGORIA -> f -> conta(noNivel(f.conta(), p.profundidade(), nomes.contas()), nomes);
            case CONTA -> f -> conta(f.conta(), nomes);
            case MEIO_PAGAMENTO -> f -> entidade(f.meio(), nomes.meios(), "Sem meio de pagamento");
            case AMBITO -> f -> entidade(f.ambito(), nomes.ambitos(), "Sem âmbito");
            case CONTRAPARTE -> CalculoDeResumo::contraparte;
        };
        List<LinhaVista> linhas = CalculoResumo.quebrar(fatos, quebra, grupo, moeda).stream()
                .map(l -> new LinhaVista(l.id(), l.nome(), l.entradas().texto(), l.saidas().texto(),
                        texto(l.participacaoEntradas()), texto(l.participacaoSaidas())))
                .toList();
        return new QuebraVista(quebra.codigo(), linhas);
    }

    private static Grupo conta(UUID id, Nomes nomes) {
        return new Grupo(id.toString(), nomes.codigos().get(id), nomes.contas().get(id).nome());
    }

    private static Grupo entidade(UUID id, Map<UUID, String> nomes, String semNome) {
        return id == null ? new Grupo("-", null, semNome) : new Grupo(id.toString(), id.toString(), nomes.get(id));
    }

    private static Grupo contraparte(FatoPeriodo f) {
        if (Argumentos.vazio(f.contraparte())) {
            return new Grupo("-", null, "Sem contraparte");
        }
        return new Grupo(Referencias.normalizar(f.contraparte()), null, f.contraparte().strip());
    }

    /** A categoria que representa {@code conta} no nível pedido; acima dele, ela mesma. */
    private static UUID noNivel(UUID conta, Integer nivel, Map<UUID, Conta> contas) {
        if (nivel == null) {
            return conta;
        }
        List<UUID> caminho = new ArrayList<>();
        for (UUID atual = conta; atual != null; atual = contas.get(atual).contaPai()) {
            caminho.add(0, atual);
        }
        return caminho.size() <= nivel ? conta : caminho.get(nivel - 1);
    }

    private List<DestaqueVisto> destaques(List<FatoPeriodo> fatos, TipoLancamento tipo, Currency moeda) {
        return CalculoResumo.maiores(fatos, tipo, MAIORES, moeda).stream()
                .map(d -> new DestaqueVisto(d.codigo(),
                        ReferenciaCurta.ler(d.codigo(), TipoReferencia.MODULO).map(enderecos::url).orElse(null),
                        d.data().toString(), d.descricao(), d.valor().texto(), d.exogeno() ? Procedencia.EXOGENO.codigo() : Procedencia.ENDOGENO.codigo()))
                .toList();
    }

    private PeriodoAnterior anterior(UUID dono, Periodo periodo, UUID ambito, String visao, Totais atual, Currency moeda) {
        Totais antes = CalculoResumo.totais(fatos(dono, periodo, ambito, visao), moeda);
        Variacao entradas = CalculoResumo.variacao(atual.entradas(), antes.entradas());
        Variacao saidas = CalculoResumo.variacao(atual.saidas(), antes.saidas());
        Variacao saldo = CalculoResumo.variacao(atual.saldo(), antes.saldo());
        return new PeriodoAnterior(periodo.de().toString(), periodo.ate().toString(), antes.entradas().texto(), antes.saidas().texto(),
                antes.saldo().texto(), entradas.diferenca().texto(), saidas.diferenca().texto(), saldo.diferenca().texto(),
                texto(entradas.percentual()), texto(saidas.percentual()), texto(saldo.percentual()));
    }

    private static String texto(BigDecimal percentual) {
        return percentual == null ? null : percentual.toPlainString();
    }

    static String visao(String texto) {
        String visao = Argumentos.vazio(texto) ? COMPETENCIA : texto.strip().toLowerCase(Locale.ROOT);
        if (!visao.equals(COMPETENCIA) && !visao.equals(CAIXA)) {
            throw ErroTipado.validacao("visao", "Visão inválida: use competencia ou caixa.");
        }
        return visao;
    }

    /** Diferença entre dois textos de dinheiro da mesma moeda. */
    static Dinheiro diferenca(String agora, String antes, Currency moeda) {
        return Dinheiro.ler(agora, moeda, "agora").subtrair(Dinheiro.ler(antes, moeda, "antes"));
    }
}
