package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.Quebra;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code resumo_periodo}. É uma leitura para o dono, mas guarda um retrato, por isso passa
 * pelo registrador. Sem chave de idempotência: repetir o pedido é um cálculo novo, com retrato novo.
 */
@Component
public class ResumirPeriodo {

    public record Entrada(String de, String ate, String periodo, List<String> agruparPor, String ambito, Integer profundidade,
            String visao, Boolean compararAnterior) {}

    static final String CAPACIDADE = "resumo_periodo";

    private static final Clock RELOGIO = Clock.systemUTC();

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioRetratos retratos;
    private final Referencias referencias;
    private final CalculoDeResumo calculo;
    private final Enderecos enderecos;
    private final Retratos leitura;

    ResumirPeriodo(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioRetratos retratos, Referencias referencias,
            CalculoDeResumo calculo, Enderecos enderecos, Retratos leitura) {
        this.registrador = registrador;
        this.donos = donos;
        this.retratos = retratos;
        this.referencias = referencias;
        this.calculo = calculo;
        this.enderecos = enderecos;
        this.leitura = leitura;
    }

    public ResumoRegistrado executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("de", e.de()).com("ate", e.ate()).com("periodo", e.periodo())
                .com("agrupar_por", Argumentos.lista(e.agruparPor())).com("ambito", e.ambito()).com("profundidade", e.profundidade())
                .com("visao", e.visao()).com("comparar_anterior", e.compararAnterior()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, null, null);
        return registrador.registrar(chamador, pedido, ResumoRegistrado.class, o -> gravar(o, chamador, e)).valor();
    }

    /** O mesmo cálculo, sem guardar retrato: a vista viva de um relatório e as vistas compostas só leem. */
    public ResumoVisto calcularAgora(Chamador chamador, Entrada e) {
        UUID dono = chamador.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        return calculo.calcular(dono, dados.moeda(), parametros(dono, e), OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC)));
    }

    private ResumoRegistrado gravar(OperacaoEmCurso operacao, Chamador chamador, Entrada e) {
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        ParametrosResumo parametros = parametros(dono, e);
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        ResumoVisto resumo = calculo.calcular(dono, dados.moeda(), parametros, agora);

        UUID id = UUID.randomUUID();
        retratos.inserir(operacao, new RepositorioRetratos.Retrato(id, CAPACIDADE, leitura.json(parametros), agora,
                leitura.json(resumo), chamador.cliente()));
        operacao.retratoGravado(id);
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.RETRATO, id);
        return new ResumoRegistrado(referencia.id(), enderecos.url(referencia),
                enderecos.urlRelatorio(parametros.de(), parametros.ate(), parametros.ambitoId()), resumo);
    }

    private ParametrosResumo parametros(UUID dono, Entrada e) {
        Periodo periodo = periodo(e.de(), e.ate(), e.periodo());
        Ambito ambito = Argumentos.vazio(e.ambito()) ? null : referencias.ambitoParaConsultar(dono, e.ambito(), "ambito");
        if (e.profundidade() != null && e.profundidade() < 1) {
            throw ErroTipado.validacao("profundidade", "A profundidade começa em 1, o nível das categorias principais.");
        }
        return new ParametrosResumo(periodo.de().toString(), periodo.ate().toString(), quebras(e.agruparPor()),
                ambito == null ? null : ambito.id().toString(), ambito == null ? null : ambito.nome(), e.profundidade(),
                CalculoDeResumo.visao(e.visao()), !Boolean.FALSE.equals(e.compararAnterior()));
    }

    /** O período por {@code periodo} ou por {@code de} e {@code ate}; o que falta volta como {@code campos_faltando}. */
    static Periodo periodo(String de, String ate, String periodo) {
        boolean porDatas = !Argumentos.vazio(de) || !Argumentos.vazio(ate);
        if (!Argumentos.vazio(periodo)) {
            if (porDatas) {
                throw ErroTipado.validacao("periodo", "Informe periodo ou de e ate, não os dois.");
            }
            return Periodo.ler(periodo, "periodo");
        }
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(de)) {
            faltando.add("de");
        }
        if (Argumentos.vazio(ate)) {
            faltando.add("ate");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono o período, ou use periodo, como 2026-09.");
        }
        return new Periodo(Datas.ler(de.strip(), "de"), Datas.ler(ate.strip(), "ate"));
    }

    /** Sem pedido, por categoria. */
    private static List<String> quebras(List<String> pedidas) {
        if (pedidas == null || pedidas.isEmpty()) {
            return List.of(Quebra.CATEGORIA.codigo());
        }
        return pedidas.stream().map(p -> Quebra.deCodigo(p == null ? "" : p.strip()).orElseThrow(() ->
                ErroTipado.validacao("agrupar_por", "Quebra inválida: use " + Quebra.opcoes() + ".")).codigo()).distinct().toList();
    }
}
