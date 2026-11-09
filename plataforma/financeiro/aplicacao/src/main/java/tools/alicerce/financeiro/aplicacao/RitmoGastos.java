package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.CalculoRitmo;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code ritmo_gastos}. Como {@code resumo_periodo}, é uma leitura para o dono que guarda
 * um retrato, e por isso passa pelo registrador. Sem chave de idempotência: repetir é um cálculo novo.
 */
@Component
public class RitmoGastos {

    /**
     * @param periodo o mês, {@code 2026-11}; sem ele, o mês de hoje
     * @param mesesReferencia quantos meses antes do analisado servem de referência; padrão 6
     */
    public record Entrada(String periodo, Integer mesesReferencia, Boolean incluirFixos, String visao) {}

    static final String CAPACIDADE = "ritmo_gastos";

    private static final Clock RELOGIO = Clock.systemUTC();

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioRetratos retratos;
    private final CalculoDeRitmo calculo;
    private final Enderecos enderecos;
    private final Retratos leitura;
    private final Declaracoes declaracoes;

    RitmoGastos(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioRetratos retratos, CalculoDeRitmo calculo,
            Enderecos enderecos, Retratos leitura, Declaracoes declaracoes) {
        this.declaracoes = declaracoes;
        this.registrador = registrador;
        this.donos = donos;
        this.retratos = retratos;
        this.calculo = calculo;
        this.enderecos = enderecos;
        this.leitura = leitura;
    }

    public RitmoRegistrado executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("periodo", e.periodo()).com("meses_referencia", e.mesesReferencia())
                .com("incluir_fixos", e.incluirFixos()).com("visao", e.visao()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, null, null);
        return registrador.registrar(chamador, pedido, RitmoRegistrado.class, o -> gravar(o, chamador, e)).valor();
    }

    private RitmoRegistrado gravar(OperacaoEmCurso operacao, Chamador chamador, Entrada e) {
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        Declaracoes.Vigente poupanca = declaracoes.vigentes(dono).get(tools.alicerce.financeiro.dominio.TipoDeclaracao.INTENCAO_POUPANCA);
        ParametrosRitmo parametros = parametros(e, LocalDate.ofInstant(agora.toInstant(), dados.fuso()), poupanca);
        RitmoVisto ritmo = calculo.calcular(dono, dados.moeda(), parametros, agora);

        UUID id = UUID.randomUUID();
        retratos.inserir(operacao, new RepositorioRetratos.Retrato(id, CAPACIDADE, leitura.json(parametros), agora,
                leitura.json(ritmo), chamador.cliente()));
        operacao.retratoGravado(id);
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.RETRATO, id);
        return new RitmoRegistrado(referencia.id(), enderecos.url(referencia), ritmo);
    }

    static ParametrosRitmo parametros(Entrada e, LocalDate hoje, Declaracoes.Vigente poupanca) {
        YearMonth mes = YearMonth.from(hoje);
        if (!Argumentos.vazio(e.periodo())) {
            try {
                mes = YearMonth.parse(e.periodo().strip());
            } catch (DateTimeParseException invalido) {
                throw ErroTipado.validacao("periodo", "Use um mês, como 2026-11.");
            }
        }
        if (mes.isAfter(YearMonth.from(hoje))) {
            throw ErroTipado.validacao("periodo", "O mês " + mes + " ainda não começou.");
        }
        int meses = e.mesesReferencia() == null ? CalculoRitmo.MESES_PADRAO : e.mesesReferencia();
        if (meses < CalculoRitmo.MESES_MINIMOS || meses > CalculoRitmo.MESES_MAXIMOS) {
            throw ErroTipado.validacao("meses_referencia", "Use de " + CalculoRitmo.MESES_MINIMOS + " a " + CalculoRitmo.MESES_MAXIMOS
                    + " meses; o padrão é " + CalculoRitmo.MESES_PADRAO + ".");
        }
        return new ParametrosRitmo(mes.toString(), hoje.toString(), meses, Boolean.TRUE.equals(e.incluirFixos()),
                CalculoDeResumo.visao(e.visao()), poupanca == null ? null
                        : ((tools.alicerce.financeiro.dominio.ValorDeclaracao.IntencaoPoupanca) poupanca.valor()).percentual().toPlainString(),
                poupanca == null ? null : poupanca.declaracao().operacao());
    }
}
