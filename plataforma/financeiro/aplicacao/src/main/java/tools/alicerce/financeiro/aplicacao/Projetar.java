package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.CalculoProjecao;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorDeclaracao;

/**
 * Capacidade {@code projetar} (história 1): o saldo mês a mês, com a linha de base e até três cenários. Como
 * {@code cortes_necessarios}, é uma leitura que guarda um retrato, e por isso passa pelo registrador. As hipóteses não
 * gravam nada: só o retrato fica. Sem chave de idempotência: repetir é um cálculo novo.
 */
@Component
public class Projetar {

    /**
     * @param horizonteMeses de 1 a 24, o mês de hoje inclusive; padrão 6
     * @param cenarios até 3
     */
    public record Entrada(Integer horizonteMeses, List<Cenario> cenarios) {}

    /** @param rotulo o nome do cenário, como "10x no Nubank"; padrão, "Cenário" e o número */
    public record Cenario(String rotulo, List<Hipotese> hipoteses) {}

    /**
     * Uma hipótese, com os campos achatados por tipo.
     *
     * @param tipo {@code compra}, {@code novo_compromisso}, {@code mudanca_renda}, {@code mudanca_categoria} ou {@code aporte_meta}
     * @param valor em compra, o total; em novo compromisso, cada ocorrência; em mudança de renda, quanto muda por mês, com sinal;
     *     em aporte em meta, quanto aportar por mês
     * @param meioPagamento em compra: o meio, como em {@code registrar_gasto}
     * @param parcelas em compra no crédito ou em compromisso parcelado
     * @param data em compra: o dia; padrão, hoje
     * @param direcao em novo compromisso: {@code saida} ou {@code entrada}
     * @param tipoCompromisso em novo compromisso: {@code recorrente} (padrão), {@code parcelado} ou {@code unico}
     * @param frequencia em novo compromisso: {@code mensal} (padrão) ou {@code anual}
     * @param primeiroVencimento em novo compromisso
     * @param fim em novo compromisso: a última data em que vence
     * @param categoria em mudança de categoria: id, nome ou alias; as subcategorias mudam junto
     * @param percentual em mudança de categoria: com sinal, como {@code -20}
     * @param de em mudança de renda ou de categoria, ou em aporte em meta: o mês, AAAA-MM, em que começa
     * @param meta em aporte em meta: id m-, nome ou alias
     * @param ate em aporte em meta: o último mês, AAAA-MM, com aporte; padrão, o fim do horizonte
     */
    public record Hipotese(String tipo, String valor, String meioPagamento, Integer parcelas, String data, String direcao,
            String tipoCompromisso, String frequencia, String primeiroVencimento, String fim, String categoria, String percentual,
            String de, String meta, String ate) {

        public Hipotese(String tipo, String valor, String meioPagamento, Integer parcelas, String data, String direcao,
                String tipoCompromisso, String frequencia, String primeiroVencimento, String fim, String categoria, String percentual,
                String de) {
            this(tipo, valor, meioPagamento, parcelas, data, direcao, tipoCompromisso, frequencia, primeiroVencimento, fim, categoria,
                    percentual, de, null, null);
        }
    }

    static final String CAPACIDADE = "projetar";
    /** Hipóteses por cenário: o bastante para comparar formas de pagar e mudanças juntas. */
    static final int HIPOTESES_MAXIMAS = 10;
    private static final java.time.Clock RELOGIO = java.time.Clock.systemUTC();

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioRetratos retratos;
    private final CalculoDeProjecao calculo;
    private final Declaracoes declaracoes;
    private final Enderecos enderecos;
    private final Retratos leitura;
    private final GastoTipico gastoTipico;

    Projetar(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioRetratos retratos, CalculoDeProjecao calculo,
            Declaracoes declaracoes, Enderecos enderecos, Retratos leitura, GastoTipico gastoTipico) {
        this.gastoTipico = gastoTipico;
        this.registrador = registrador;
        this.donos = donos;
        this.retratos = retratos;
        this.calculo = calculo;
        this.declaracoes = declaracoes;
        this.enderecos = enderecos;
        this.leitura = leitura;
    }

    public ProjecaoRegistrada executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("horizonte_meses", e.horizonteMeses())
                .com("cenarios", argumentos(e.cenarios())).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, null, null);
        return registrador.registrar(chamador, pedido, ProjecaoRegistrada.class, o -> gravar(o, chamador, e)).valor();
    }

    /** Os cenários como o registrador grava os argumentos: mapas com os nomes que o assistente usou. */
    private static List<Object> argumentos(List<Cenario> cenarios) {
        if (cenarios == null) {
            return null;
        }
        List<Object> lista = new ArrayList<>();
        for (Cenario c : cenarios) {
            List<Object> hipoteses = new ArrayList<>();
            if (c != null && c.hipoteses() != null) {
                for (Hipotese h : c.hipoteses()) {
                    hipoteses.add(h == null ? null : new Argumentos().com("tipo", h.tipo()).com("valor", h.valor())
                            .com("meio_pagamento", h.meioPagamento()).com("parcelas", h.parcelas()).com("data", h.data())
                            .com("direcao", h.direcao()).com("tipo_compromisso", h.tipoCompromisso()).com("frequencia", h.frequencia())
                            .com("primeiro_vencimento", h.primeiroVencimento()).com("fim", h.fim()).com("categoria", h.categoria())
                            .com("percentual", h.percentual()).com("de", h.de()).com("meta", h.meta()).com("ate", h.ate()).mapa());
                }
            }
            lista.add(new Argumentos().com("rotulo", c == null ? null : c.rotulo()).com("hipoteses", hipoteses).mapa());
        }
        return lista;
    }

    private ProjecaoRegistrada gravar(OperacaoEmCurso operacao, Chamador chamador, Entrada e) {
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        LocalDate hoje = LocalDate.ofInstant(agora.toInstant(), dados.fuso());
        Map<TipoDeclaracao, Declaracoes.Vigente> vigentes = declaracoes.vigentes(dono);
        Declaracoes.Vigente piso = vigentes.get(TipoDeclaracao.PISO_PERIODO);
        ParametrosProjecao parametros = parametros(e, hoje, dados, piso, regras(declaracoes, vigentes, () -> gastoTipico.calcular(dono, hoje),
                dados.moeda()));
        ProjecaoVista projecao = calculo.calcular(dono, dados.moeda(), parametros, agora);

        UUID id = UUID.randomUUID();
        retratos.inserir(operacao, new RepositorioRetratos.Retrato(id, CAPACIDADE, leitura.json(parametros), agora, leitura.json(projecao),
                chamador.cliente()));
        operacao.retratoGravado(id);
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.RETRATO, id);
        return new ProjecaoRegistrada(referencia.id(), enderecos.url(referencia), projecao);
    }

    /** As regras declaradas que a projeção vigia, resolvidas hoje; a reserva em meses, pelo gasto típico de agora. */
    static ParametrosProjecao.Regras regras(Declaracoes declaracoes, Map<TipoDeclaracao, Declaracoes.Vigente> vigentes,
            java.util.function.Supplier<GastoTipico.Visto> gasto, java.util.Currency moeda) {
        java.util.Optional<Declaracoes.Reserva> reserva = declaracoes.reserva(vigentes, gasto);
        Declaracoes.Vigente teto = vigentes.get(TipoDeclaracao.TETO_PARCELAS);
        Declaracoes.Vigente metas = vigentes.get(TipoDeclaracao.META_PROTEGIDA);
        GastoTipico.Visto g = reserva.map(Declaracoes.Reserva::gasto).orElse(null);
        return new ParametrosProjecao.Regras(
                reserva.map(Declaracoes.Reserva::centavos).map(c -> Dinheiro.deCentavos(c, moeda).texto()).orElse(null),
                reserva.map(r -> r.declarada().mesesDeGasto()).orElse(null),
                g == null || g.centavos() == null ? null : Dinheiro.deCentavos(g.centavos(), moeda).texto(),
                g == null ? null : g.meses().stream().map(java.time.YearMonth::toString).toList(),
                reserva.isPresent() && reserva.get().centavos() == null, reserva.map(Declaracoes.Reserva::operacao).orElse(null),
                teto == null ? null : ((ValorDeclaracao.TetoParcelas) teto.valor()).percentual().toPlainString(),
                teto == null ? null : teto.declaracao().operacao(),
                metas == null ? List.of() : ((ValorDeclaracao.MetaProtegida) metas.valor()).metas().stream().map(UUID::toString).toList(),
                metas == null ? null : metas.declaracao().operacao());
    }

    static ParametrosProjecao parametros(Entrada e, LocalDate hoje, Dono dados, Declaracoes.Vigente piso, ParametrosProjecao.Regras regras) {
        int horizonte = e.horizonteMeses() == null ? CalculoProjecao.HORIZONTE_PADRAO : e.horizonteMeses();
        if (horizonte < 1 || horizonte > CalculoProjecao.HORIZONTE_MAXIMO) {
            throw ErroTipado.validacao("horizonte_meses", "O horizonte vai de 1 a " + CalculoProjecao.HORIZONTE_MAXIMO
                    + " meses, contando o mês de hoje.");
        }
        List<Cenario> pedidos = e.cenarios() == null ? List.of() : e.cenarios();
        if (pedidos.size() > CalculoProjecao.CENARIOS_MAXIMOS) {
            throw ErroTipado.validacao("cenarios", "Até " + CalculoProjecao.CENARIOS_MAXIMOS + " cenários por chamada; a linha de base vem "
                    + "sempre. Para comparar mais, chame de novo.");
        }
        List<Cenario> cenarios = new ArrayList<>();
        for (int i = 0; i < pedidos.size(); i++) {
            Cenario c = pedidos.get(i);
            if (c == null || c.hipoteses() == null || c.hipoteses().isEmpty()) {
                throw ErroTipado.camposFaltando(List.of("cenarios[" + i + "].hipoteses"), "Cada cenário precisa de pelo menos uma hipótese: "
                        + "o que o dono quer simular (uma compra, um novo compromisso, uma mudança de renda ou de categoria, um aporte em meta).");
            }
            if (c.hipoteses().size() > HIPOTESES_MAXIMAS) {
                throw ErroTipado.validacao("cenarios[" + i + "].hipoteses", "Até " + HIPOTESES_MAXIMAS + " hipóteses por cenário.");
            }
            String rotulo = Argumentos.vazio(c.rotulo()) ? "Cenário " + (i + 1) : c.rotulo().strip();
            cenarios.add(new Cenario(rotulo, List.copyOf(c.hipoteses())));
        }
        String valorPiso = piso == null ? null
                : Dinheiro.deCentavos(((ValorDeclaracao.PisoPeriodo) piso.valor()).saldoMinimoCentavos(), dados.moeda()).texto();
        return new ParametrosProjecao(hoje.toString(), horizonte, valorPiso, piso == null ? null : piso.declaracao().operacao(), cenarios, regras);
    }
}
