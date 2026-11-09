package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Filtro;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Operacao;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Posicao;

/** Capacidade {@code listar_operacoes}: sem filtros, as 20 mais recentes ainda aplicadas. */
@Component
public class ListarOperacoes {

    public record Entrada(String lote, String cliente, String de, String ate, String capacidade, Boolean incluirDesfeitas,
            String cursor) {}

    public record Pagina(List<OperacaoVista> operacoes, String proximoCursor) {}

    static final int SEM_FILTROS = 20;
    static final int COM_FILTROS = 50;

    private final RepositorioHistorico historico;
    private final RepositorioDono donos;
    private final ResumoOperacao resumo;
    private final Referencias referencias;

    /** As capacidades que só guardam um retrato: leituras, que não se desfazem. */
    static final java.util.Set<String> SO_RETRATO = java.util.Set.of(ResumirPeriodo.CAPACIDADE, RitmoGastos.CAPACIDADE,
            CortesNecessarios.CAPACIDADE, Projetar.CAPACIDADE);

    ListarOperacoes(RepositorioHistorico historico, RepositorioDono donos, ResumoOperacao resumo, Referencias referencias) {
        this.historico = historico;
        this.donos = donos;
        this.resumo = resumo;
        this.referencias = referencias;
    }

    /** Uma operação do dono, pelo id curto. */
    public OperacaoVista ver(Chamador chamador, String ref) {
        UUID dono = chamador.dono();
        return resumo.ver(historico.buscar(dono, referencias.operacao(dono, ref, "ref")).orElseThrow());
    }

    public Pagina executar(Chamador chamador, Entrada e) {
        UUID dono = chamador.dono();
        ZoneId fuso = donos.buscar(dono).orElseThrow().fuso();
        boolean semFiltros = Argumentos.vazio(e.lote()) && Argumentos.vazio(e.cliente()) && Argumentos.vazio(e.de())
                && Argumentos.vazio(e.ate()) && Argumentos.vazio(e.capacidade());
        int tamanho = semFiltros ? SEM_FILTROS : COM_FILTROS;
        Filtro filtro = new Filtro(texto(e.lote()), texto(e.cliente()), inicio(e.de(), "de", fuso, 0),
                inicio(e.ate(), "ate", fuso, 1), texto(e.capacidade()),
                // Um relatório ou um ritmo guarda retrato, mas não mudou nada: só aparece se for pedido pelo nome.
                Argumentos.vazio(e.capacidade()) ? SO_RETRATO : java.util.Set.of(), Boolean.TRUE.equals(e.incluirDesfeitas()),
                Argumentos.vazio(e.cursor()) ? null : posicao(e.cursor()));
        List<Operacao> lidas = historico.listar(dono, filtro, tamanho + 1);
        boolean temMais = lidas.size() > tamanho;
        List<Operacao> pagina = temMais ? lidas.subList(0, tamanho) : lidas;
        String proximo = null;
        if (temMais) {
            Operacao ultima = pagina.get(pagina.size() - 1);
            proximo = Cursores.escrever(ultima.criadaEm().toString(), ultima.id().toString());
        }
        return new Pagina(pagina.stream().map(resumo::ver).toList(), proximo);
    }

    private static String texto(String valor) {
        return Argumentos.vazio(valor) ? null : valor.strip();
    }

    /** O começo do dia no fuso do dono, somando {@code dias}: {@code ate} inclui o dia inteiro. */
    private static OffsetDateTime inicio(String texto, String campo, ZoneId fuso, int dias) {
        if (Argumentos.vazio(texto)) {
            return null;
        }
        LocalDate dia = Datas.ler(texto.strip(), campo).plusDays(dias);
        return dia.atStartOfDay(fuso).toOffsetDateTime();
    }

    private static Posicao posicao(String cursor) {
        List<String> partes = Cursores.ler(cursor, 2);
        try {
            return new Posicao(OffsetDateTime.parse(partes.get(0)), UUID.fromString(partes.get(1)));
        } catch (DateTimeParseException | IllegalArgumentException invalido) {
            throw ErroTipado.validacao("cursor", Cursores.invalido().mensagem());
        }
    }
}
