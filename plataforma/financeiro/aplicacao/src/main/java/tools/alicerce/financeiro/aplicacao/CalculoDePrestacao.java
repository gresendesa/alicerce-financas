package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.PrestacaoVista.CategoriaPrestada;
import tools.alicerce.financeiro.aplicacao.PrestacaoVista.ComprovantePrestado;
import tools.alicerce.financeiro.aplicacao.PrestacaoVista.LancamentoPrestado;
import tools.alicerce.financeiro.aplicacao.PrestacaoVista.NotaPrestada;
import tools.alicerce.financeiro.dominio.CalculoResumo;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.NaturezaImagem;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoMeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Monta a prestação de contas de um âmbito. Totais e categorias são os de {@code resumo_periodo} filtrado pelo
 * âmbito, em competência; os lançamentos vêm do mesmo recorte. Só lê: quem guarda o retrato é a capacidade.
 */
@Component
class CalculoDePrestacao {

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CalculoDeResumo resumo;
    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioComprovantes comprovantes;

    CalculoDePrestacao(CalculoDeResumo resumo, RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura,
            RepositorioComprovantes comprovantes) {
        this.resumo = resumo;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.comprovantes = comprovantes;
    }

    PrestacaoVista calcular(UUID dono, Currency moeda, ParametrosPrestacao p, LocalDate validaAte, OffsetDateTime agora) {
        ResumoVisto r = resumo.calcular(dono, moeda, p.resumo(), agora);
        List<CategoriaPrestada> categorias = r.quebras().getFirst().linhas().stream()
                .map(l -> new CategoriaPrestada(l.nome(), l.saidas(), l.entradas())).toList();
        UUID ambito = UUID.fromString(p.ambitoId());
        List<CalculoResumo.LancamentoDoPeriodo> doPeriodo = CalculoResumo.lancamentos(
                lancamentos.fatosDoPeriodo(dono, LocalDate.parse(p.de()), LocalDate.parse(p.ate()), ambito), moeda);
        Map<UUID, TipoMeioPagamento> meios = estrutura.meios(dono).stream().collect(Collectors.toMap(MeioPagamento::id, MeioPagamento::tipo));
        List<LancamentoPrestado> linhas = doPeriodo.stream().map(l -> new LancamentoPrestado(l.data().toString(),
                l.tipo() == TipoLancamento.GANHO ? "Entrada" : "Gasto", l.descricao(), l.contraparte(),
                l.meio() == null ? null : rotulo(meios.get(l.meio())), l.valor().texto())).toList();
        List<ComprovantePrestado> imagens = p.incluirComprovantes() ? imagens(dono, doPeriodo) : null;
        List<NotaPrestada> notas = p.incluirComprovantes() ? notas(dono, doPeriodo) : null;
        return new PrestacaoVista(p.ambito(), p.de(), p.ate(), moeda.getCurrencyCode(), r.saidas(), r.entradas(), r.quantidadeLancamentos(),
                validaAte.toString(), categorias, linhas, imagens, notas);
    }

    /** Só comprovante: foto é lembrança e fica fora. */
    private List<ComprovantePrestado> imagens(UUID dono, List<CalculoResumo.LancamentoDoPeriodo> doPeriodo) {
        List<ComprovantePrestado> lista = new ArrayList<>();
        for (CalculoResumo.LancamentoDoPeriodo l : doPeriodo) {
            for (RepositorioComprovantes.Indice i : comprovantes.doLancamento(dono, l.lancamento())) {
                if (!NaturezaImagem.COMPROVANTE.codigo().equals(i.natureza())) {
                    continue;
                }
                String codigo = estrutura.referenciaDe(dono, TipoReferencia.COMPROVANTE, i.id()).orElseThrow();
                lista.add(new ComprovantePrestado(codigo, "Comprovante: " + l.descricao() + ", " + l.data().format(DIA), null));
            }
        }
        return lista;
    }

    private List<NotaPrestada> notas(UUID dono, List<CalculoResumo.LancamentoDoPeriodo> doPeriodo) {
        List<NotaPrestada> lista = new ArrayList<>();
        for (CalculoResumo.LancamentoDoPeriodo l : doPeriodo) {
            lancamentos.chaveDoLancamento(dono, l.lancamento())
                    .ifPresent(chave -> lista.add(new NotaPrestada(l.data().toString(), l.descricao(), emGrupos(chave))));
        }
        return lista;
    }

    /** Como no DANFE: grupos de quatro dígitos, que se leem e quebram linha no celular. */
    static String emGrupos(String chave) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < chave.length(); i += 4) {
            if (i > 0) {
                texto.append(' ');
            }
            texto.append(chave, i, Math.min(i + 4, chave.length()));
        }
        return texto.toString();
    }

    /** Só o tipo do meio: o nome dele (como "Nubank do Gui") nunca sai na página pública. */
    static String rotulo(TipoMeioPagamento tipo) {
        if (tipo == null) {
            return null;
        }
        return switch (tipo) {
            case PIX -> "Pix";
            case DEBITO -> "Débito";
            case CREDITO -> "Crédito";
            case DINHEIRO -> "Dinheiro";
            case TRANSFERENCIA -> "Transferência";
            case BOLETO -> "Boleto";
        };
    }
}
