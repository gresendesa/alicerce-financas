package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.CalculoCortes;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorDeclaracao;

/**
 * Capacidade {@code cortes_necessarios} (história 1) no mês corrente. Como {@code ritmo_gastos}, é uma leitura
 * que guarda um retrato, e por isso passa pelo registrador. Sem chave de idempotência: repetir é um cálculo novo.
 */
@Component
public class CortesNecessarios {

    /**
     * @param alvo {@code piso_periodo} (a restrição declarada) ou {@code saldo_final}; sem ele, {@code saldo_final} se veio
     *     um saldo, senão {@code piso_periodo}
     * @param saldoFinal em {@code saldo_final}: o saldo mínimo na data
     * @param data em {@code saldo_final}: uma data de hoje até o fim do mês; padrão, o último dia do mês
     * @param estimativa {@code p80} (padrão, conservadora) ou {@code p50}
     * @param meta com alvo {@code meta}: id m-, nome ou alias; sem alvo, uma meta escolhe o alvo {@code meta}
     */
    public record Entrada(String alvo, String saldoFinal, String data, String estimativa, String meta) {

        public Entrada(String alvo, String saldoFinal, String data, String estimativa) {
            this(alvo, saldoFinal, data, estimativa, null);
        }
    }

    static final String CAPACIDADE = "cortes_necessarios";
    private static final String PISO = "piso_periodo";
    private static final String SALDO_FINAL = "saldo_final";
    private static final String META = "meta";
    private static final java.time.Clock RELOGIO = java.time.Clock.systemUTC();

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioRetratos retratos;
    private final CalculoDeCortes calculo;
    private final Declaracoes declaracoes;
    private final Enderecos enderecos;
    private final Retratos leitura;
    private final Metas metas;

    CortesNecessarios(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioRetratos retratos, CalculoDeCortes calculo,
            Declaracoes declaracoes, Enderecos enderecos, Retratos leitura, Metas metas) {
        this.metas = metas;
        this.registrador = registrador;
        this.donos = donos;
        this.retratos = retratos;
        this.calculo = calculo;
        this.declaracoes = declaracoes;
        this.enderecos = enderecos;
        this.leitura = leitura;
    }

    public CortesRegistrado executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("alvo", e.alvo()).com("saldo_final", e.saldoFinal()).com("data", e.data())
                .com("estimativa", e.estimativa()).com("meta", e.meta()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, null, null);
        return registrador.registrar(chamador, pedido, CortesRegistrado.class, o -> gravar(o, chamador, e)).valor();
    }

    private CortesRegistrado gravar(OperacaoEmCurso operacao, Chamador chamador, Entrada e) {
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        LocalDate hoje = LocalDate.ofInstant(agora.toInstant(), dados.fuso());
        Map<TipoDeclaracao, Declaracoes.Vigente> vigentes = declaracoes.vigentes(dono);
        Declaracoes.Vigente piso = vigentes.get(TipoDeclaracao.PISO_PERIODO);
        ParametrosCortes parametros = parametros(e, hoje, dados, piso, vigentes.get(TipoDeclaracao.CATEGORIAS_ESSENCIAIS));
        if (parametros.alvo().equals(META)) {
            parametros = comMeta(dono, parametros, e, hoje, dados, piso);
        }
        String operacaoPiso = parametros.alvo().equals(PISO) || parametros.alvo().equals(META) && piso != null ? piso.declaracao().operacao() : null;
        CortesVisto cortes = calculo.calcular(dono, dados.moeda(), parametros, operacaoPiso, agora);

        UUID id = UUID.randomUUID();
        retratos.inserir(operacao, new RepositorioRetratos.Retrato(id, CAPACIDADE, leitura.json(parametros), agora, leitura.json(cortes),
                chamador.cliente()));
        operacao.retratoGravado(id);
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.RETRATO, id);
        return new CortesRegistrado(referencia.id(), enderecos.url(referencia), cortes);
    }

    /**
     * Alvo meta: o saldo do fim do mês tem de cobrir o piso declarado (zero sem piso) mais o aporte
     * mensal necessário da meta, calculado no domínio.
     */
    private ParametrosCortes comMeta(UUID dono, ParametrosCortes p, Entrada e, LocalDate hoje, Dono dados, Declaracoes.Vigente piso) {
        if (Argumentos.vazio(e.meta())) {
            throw ErroTipado.camposFaltando(List.of("meta"), "Pergunte ao dono para qual meta ele quer guardar neste mês.");
        }
        tools.alicerce.financeiro.dominio.Meta meta = metas.resolver(dono, e.meta(), "meta");
        if (!meta.emAndamento()) {
            throw ErroTipado.validacao("meta", "A meta '" + meta.nome() + "' está " + meta.situacao().codigo() + ": não pede aporte.");
        }
        if (!meta.moeda().equals(dados.moeda().getCurrencyCode())) {
            throw ErroTipado.validacao("meta", "Os cortes são na moeda do dono, e a meta está em " + meta.moeda() + ".");
        }
        var situacao = metas.situacao(meta, metas.progresso(meta.id(), dono), hoje);
        if (situacao.aporteMensal() == null) {
            throw ErroTipado.validacao("meta", "A meta '" + meta.nome() + "' não tem data alvo, então não há aporte necessário no mês. "
                    + "Pergunte ao dono até quando ele quer juntar e grave com definir_meta, ou use alvo saldo_final com o valor que ele quer guardar.");
        }
        long pisoCentavos = piso == null ? 0 : ((ValorDeclaracao.PisoPeriodo) piso.valor()).saldoMinimoCentavos();
        long alvo = Math.addExact(pisoCentavos, situacao.aporteMensal());
        String codigo = metas.ver(dono, meta, situacao.progresso(), hoje).id();
        return new ParametrosCortes(p.hoje(), p.fim(), META, Dinheiro.deCentavos(alvo, dados.moeda()).texto(), p.estimativa(), p.essenciais(),
                codigo, meta.nome(), Dinheiro.deCentavos(situacao.aporteMensal(), dados.moeda()).texto(),
                Dinheiro.deCentavos(pisoCentavos, dados.moeda()).texto());
    }

    static ParametrosCortes parametros(Entrada e, LocalDate hoje, Dono dados, Declaracoes.Vigente piso, Declaracoes.Vigente essenciais) {
        String alvo = Argumentos.vazio(e.alvo()) ? (!Argumentos.vazio(e.meta()) ? META : Argumentos.vazio(e.saldoFinal()) ? PISO : SALDO_FINAL)
                : e.alvo().strip();
        if (!alvo.equals(META) && !Argumentos.vazio(e.meta())) {
            throw ErroTipado.validacao("meta", "meta só vale com alvo meta.");
        }
        String estimativa = Argumentos.vazio(e.estimativa()) ? CalculoCortes.Estimativa.P80.codigo() : e.estimativa().strip();
        if (CalculoCortes.Estimativa.deCodigo(estimativa).isEmpty()) {
            throw ErroTipado.validacao("estimativa", "Estimativa inválida: use p80 (conservadora, o padrão) ou p50 (otimista, a mediana).");
        }
        LocalDate fimDoMes = CalculoCortes.fimDoPeriodo(hoje);
        String valor;
        LocalDate fim;
        switch (alvo) {
            case PISO -> {
                if (!Argumentos.vazio(e.saldoFinal()) || !Argumentos.vazio(e.data())) {
                    throw ErroTipado.validacao("alvo", "Com alvo piso_periodo, o saldo vem da declaração: não envie saldo_final nem data.");
                }
                if (piso == null) {
                    throw ErroTipado.camposFaltando(List.of("saldo_final"), "O dono ainda não declarou com quanto quer fechar o mês. Pergunte e "
                            + "registre com declarar (tipo piso_periodo), ou envie saldo_final para um cálculo avulso.");
                }
                valor = Dinheiro.deCentavos(((ValorDeclaracao.PisoPeriodo) piso.valor()).saldoMinimoCentavos(), dados.moeda()).texto();
                fim = fimDoMes;
            }
            case SALDO_FINAL -> {
                if (Argumentos.vazio(e.saldoFinal())) {
                    throw ErroTipado.camposFaltando(List.of("saldo_final"), "Pergunte ao dono com quanto ele quer chegar, e em que data do mês.");
                }
                valor = Dinheiro.ler(e.saldoFinal(), dados.moeda(), "saldo_final").texto();
                fim = Argumentos.vazio(e.data()) ? fimDoMes : Datas.ler(e.data().strip(), "data");
                if (fim.isBefore(hoje) || fim.isAfter(fimDoMes)) {
                    throw ErroTipado.validacao("data", "A data é de hoje (" + hoje + ") até o fim do mês (" + fimDoMes
                            + "). Para os meses seguintes, use projetar.");
                }
            }
            case META -> {
                if (!Argumentos.vazio(e.saldoFinal()) || !Argumentos.vazio(e.data())) {
                    throw ErroTipado.validacao("alvo", "Com alvo meta, o saldo vem do piso declarado e do aporte da meta: não envie saldo_final nem data.");
                }
                valor = null;
                fim = fimDoMes;
            }
            default -> throw ErroTipado.validacao("alvo", "Alvo inválido: use piso_periodo (a restrição declarada), saldo_final (um saldo "
                    + "avulso numa data do mês) ou meta (fechar no piso e ainda guardar o aporte do mês para uma meta).");
        }
        List<String> ids = essenciais == null ? List.of()
                : ((ValorDeclaracao.CategoriasEssenciais) essenciais.valor()).categorias().stream().map(UUID::toString).toList();
        return new ParametrosCortes(hoje.toString(), fim.toString(), alvo, valor, estimativa, ids);
    }
}
