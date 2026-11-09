package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioDeclaracoes.Declaracao;
import tools.alicerce.financeiro.dominio.CalculoPerfil;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorDeclaracao;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lê e escreve as declarações do perfil. A vigente de cada tipo é a última gravada de operação aplicada: desfazer a
 * nova traz de volta a anterior, sem UPDATE.
 */
@Component
class Declaracoes {

    private final JsonMapper json = JsonMapper.builder().build();
    private final RepositorioDeclaracoes repositorio;
    private final RepositorioEstrutura estrutura;
    private final Enderecos enderecos;
    private final GastoTipico gastoTipico;
    private final RepositorioMetas metas;
    private final RepositorioDono donos;

    Declaracoes(RepositorioDeclaracoes repositorio, RepositorioEstrutura estrutura, Enderecos enderecos, GastoTipico gastoTipico,
            RepositorioMetas metas, RepositorioDono donos) {
        this.gastoTipico = gastoTipico;
        this.metas = metas;
        this.donos = donos;
        this.repositorio = repositorio;
        this.estrutura = estrutura;
        this.enderecos = enderecos;
    }

    /** Uma declaração com o valor já lido. */
    record Vigente(Declaracao declaracao, ValorDeclaracao valor) {}

    /** A vigente de cada tipo que tem alguma. */
    Map<TipoDeclaracao, Vigente> vigentes(UUID dono) {
        Map<TipoDeclaracao, Vigente> vigentes = new EnumMap<>(TipoDeclaracao.class);
        for (Declaracao d : repositorio.declaracoes(dono)) {
            vigentes.computeIfAbsent(d.tipo(), t -> new Vigente(d, ler(d)));
        }
        return vigentes;
    }

    Optional<ValorDeclaracao.PisoPeriodo> piso(UUID dono) {
        return Optional.ofNullable(vigentes(dono).get(TipoDeclaracao.PISO_PERIODO)).map(v -> (ValorDeclaracao.PisoPeriodo) v.valor());
    }

    List<UUID> essenciais(UUID dono) {
        return Optional.ofNullable(vigentes(dono).get(TipoDeclaracao.CATEGORIAS_ESSENCIAIS))
                .map(v -> ((ValorDeclaracao.CategoriasEssenciais) v.valor()).categorias()).orElse(List.of());
    }

    /** O valor vigente de um tipo, já lido; vazio sem declaração. */
    <T extends ValorDeclaracao> Optional<T> valor(Map<TipoDeclaracao, Vigente> vigentes, TipoDeclaracao tipo, Class<T> classe) {
        return Optional.ofNullable(vigentes.get(tipo)).map(v -> classe.cast(v.valor()));
    }

    /**
     * A reserva mínima de hoje, em centavos.
     *
     * @param centavos nulo se é em meses e não há gasto típico
     * @param gasto o gasto típico usado; nulo na reserva por valor
     */
    record Reserva(ValorDeclaracao.ReservaMinima declarada, Long centavos, GastoTipico.Visto gasto, String operacao) {}

    /** A reserva vigente com o valor que vale hoje; em meses, pelo gasto típico da base dada (decisão 1). */
    Optional<Reserva> reserva(Map<TipoDeclaracao, Vigente> vigentes, java.util.function.Supplier<GastoTipico.Visto> gasto) {
        Vigente v = vigentes.get(TipoDeclaracao.RESERVA_MINIMA);
        if (v == null) {
            return Optional.empty();
        }
        ValorDeclaracao.ReservaMinima r = (ValorDeclaracao.ReservaMinima) v.valor();
        GastoTipico.Visto g = r.mesesDeGasto() == null ? null : gasto.get();
        return Optional.of(new Reserva(r, CalculoPerfil.reserva(r, g == null ? null : g.centavos()), g, v.declaracao().operacao()));
    }

    /** A ressalva da reserva em meses sem gasto típico. */
    static String semGastoTipico(GastoTipico.Visto g) {
        return "A reserva em meses de gasto ficou sem valor: só " + g.meses().size() + " meses de referência têm histórico completo, e são "
                + "precisos " + tools.alicerce.financeiro.dominio.CalculoRitmo.MESES_MINIMOS + " para o gasto mensal típico."
                + (g.disponivelEm() == null ? "" : " O valor passa a sair em " + g.disponivelEm() + ", sem o dono fazer nada.")
                + " Não estime o valor nem a data por conta própria.";
    }

    /** As vigentes como o assistente as vê, na ordem dos tipos. */
    List<DeclaracaoVista> vistas(UUID dono, Currency moeda) {
        Map<UUID, Conta> contas = contas(dono);
        Map<UUID, String> codigos = estrutura.referenciasDeConta(dono);
        List<DeclaracaoVista> lista = new ArrayList<>();
        Leitura leitura = new Leitura(dono, moeda, contas, codigos);
        vigentes(dono).values().forEach(v -> lista.add(vista(v, leitura)));
        return lista;
    }

    DeclaracaoVista vista(UUID dono, Vigente v, Currency moeda) {
        return vista(v, new Leitura(dono, moeda, contas(dono), estrutura.referenciasDeConta(dono)));
    }

    /** O que a vista precisa ler uma vez por chamada. */
    private record Leitura(UUID dono, Currency moeda, Map<UUID, Conta> contas, Map<UUID, String> codigos) {}

    private Map<UUID, Conta> contas(UUID dono) {
        return estrutura.contas(dono).stream().collect(Collectors.toMap(Conta::id, Function.identity()));
    }

    private DeclaracaoVista vista(Vigente v, Leitura l) {
        Declaracao d = v.declaracao();
        Currency moeda = l.moeda();
        String url = d.operacao() == null ? null : ReferenciaCurta.ler(d.operacao(), TipoReferencia.MODULO).map(enderecos::url).orElse(null);
        String registrada = d.registradaEm() == null ? null : d.registradaEm().toString();
        Function<Long, String> dinheiro = c -> Dinheiro.deCentavos(c, moeda).texto();
        Campos c = new Campos();
        switch (v.valor()) {
            case ValorDeclaracao.PisoPeriodo p -> {
                c.periodo = ValorDeclaracao.PisoPeriodo.PERIODO;
                c.saldoMinimo = dinheiro.apply(p.saldoMinimoCentavos());
                c.contasPagas = true;
            }
            case ValorDeclaracao.CategoriasEssenciais e -> c.categorias = e.categorias().stream().map(id -> {
                Conta conta = l.contas().get(id);
                return new DeclaracaoVista.CategoriaDeclarada(l.codigos().get(id), conta == null ? null : conta.nome(),
                        conta != null && conta.arquivada());
            }).toList();
            case ValorDeclaracao.ReservaMinima r -> {
                if (r.valorCentavos() != null) {
                    c.valor = dinheiro.apply(r.valorCentavos());
                    c.equivalente = c.valor;
                } else {
                    GastoTipico.Visto g = gastoTipico.calcular(l.dono(), hoje(l.dono()));
                    c.meses = r.mesesDeGasto();
                    c.mesesGasto = g.meses().stream().map(java.time.YearMonth::toString).toList();
                    if (g.centavos() == null) {
                        c.ressalva = semGastoTipico(g);
                    } else {
                        c.gasto = dinheiro.apply(g.centavos());
                        c.equivalente = dinheiro.apply(CalculoPerfil.reserva(r, g.centavos()));
                    }
                }
            }
            case ValorDeclaracao.IntencaoPoupanca i -> c.percentual = i.percentual().toPlainString();
            case ValorDeclaracao.TetoParcelas t -> c.percentual = t.percentual().toPlainString();
            case ValorDeclaracao.MetaProtegida m -> {
                Map<UUID, Meta> porId = metas.metas(l.dono()).stream().collect(Collectors.toMap(Meta::id, Function.identity()));
                Map<UUID, String> codigosMeta = metas.referencias(l.dono());
                c.metas = m.metas().stream().map(id -> {
                    Meta meta = porId.get(id);
                    String codigo = meta == null || meta.arquivada() ? null : codigosMeta.get(id);
                    String urlMeta = codigo == null ? null : ReferenciaCurta.ler(codigo, TipoReferencia.MODULO).map(enderecos::url).orElse(null);
                    return new DeclaracaoVista.MetaDeclarada(codigo, urlMeta, meta == null ? null : meta.nome(),
                            meta == null || meta.dataAlvo() == null ? null : meta.dataAlvo().toString(),
                            meta == null ? null : meta.arquivada() ? "arquivada" : meta.situacao().codigo());
                }).toList();
            }
            case ValorDeclaracao.Nota n -> c.texto = n.texto();
        }
        return new DeclaracaoVista(d.tipo().codigo(), d.vigenteDesde().toString(), registrada, c.periodo, c.saldoMinimo, c.contasPagas,
                c.categorias, c.valor, c.meses, c.equivalente, c.gasto, c.mesesGasto, c.percentual, c.metas, c.texto, c.ressalva,
                d.observacao(), d.operacao(), url);
    }

    /** Os campos da vista que cada tipo preenche. */
    private static final class Campos {
        String periodo;
        String saldoMinimo;
        Boolean contasPagas;
        List<DeclaracaoVista.CategoriaDeclarada> categorias;
        String valor;
        Integer meses;
        String equivalente;
        String gasto;
        List<String> mesesGasto;
        String percentual;
        List<DeclaracaoVista.MetaDeclarada> metas;
        String texto;
        String ressalva;
    }

    private java.time.LocalDate hoje(UUID dono) {
        return tools.alicerce.convencoes.Datas.hoje(java.time.Clock.systemUTC(), donos.buscar(dono).orElseThrow().fuso());
    }

    String escrever(ValorDeclaracao valor) {
        ObjectNode no = json.createObjectNode();
        switch (valor) {
            case ValorDeclaracao.PisoPeriodo p -> {
                no.put("periodo", ValorDeclaracao.PisoPeriodo.PERIODO);
                no.put("saldo_minimo_centavos", p.saldoMinimoCentavos());
                no.put("contas_pagas", true);
            }
            case ValorDeclaracao.CategoriasEssenciais c -> {
                ArrayNode lista = no.putArray("categorias");
                c.categorias().forEach(id -> lista.add(id.toString()));
            }
            case ValorDeclaracao.ReservaMinima r -> {
                if (r.valorCentavos() != null) {
                    no.put("valor_centavos", r.valorCentavos());
                } else {
                    no.put("meses_de_gasto", r.mesesDeGasto());
                }
            }
            case ValorDeclaracao.IntencaoPoupanca i -> no.put("percentual", i.percentual().toPlainString());
            case ValorDeclaracao.TetoParcelas t -> no.put("percentual", t.percentual().toPlainString());
            case ValorDeclaracao.MetaProtegida m -> {
                ArrayNode lista = no.putArray("metas");
                m.metas().forEach(id -> lista.add(id.toString()));
            }
            case ValorDeclaracao.Nota n -> no.put("texto", n.texto());
        }
        return json.writeValueAsString(no);
    }

    ValorDeclaracao ler(Declaracao d) {
        JsonNode no = json.readTree(d.valor());
        return switch (d.tipo()) {
            case PISO_PERIODO -> new ValorDeclaracao.PisoPeriodo(no.get("saldo_minimo_centavos").asLong());
            case CATEGORIAS_ESSENCIAIS -> {
                List<UUID> ids = new ArrayList<>();
                no.get("categorias").forEach(id -> ids.add(UUID.fromString(id.asString())));
                yield new ValorDeclaracao.CategoriasEssenciais(ids);
            }
            case RESERVA_MINIMA -> no.has("valor_centavos") ? new ValorDeclaracao.ReservaMinima(no.get("valor_centavos").asLong(), null)
                    : new ValorDeclaracao.ReservaMinima(null, no.get("meses_de_gasto").asInt());
            case INTENCAO_POUPANCA -> new ValorDeclaracao.IntencaoPoupanca(new BigDecimal(no.get("percentual").asString()));
            case TETO_PARCELAS -> new ValorDeclaracao.TetoParcelas(new BigDecimal(no.get("percentual").asString()));
            case META_PROTEGIDA -> {
                List<UUID> ids = new ArrayList<>();
                no.get("metas").forEach(id -> ids.add(UUID.fromString(id.asString())));
                yield new ValorDeclaracao.MetaProtegida(ids);
            }
            case NOTA -> new ValorDeclaracao.Nota(no.get("texto").asString());
        };
    }
}
