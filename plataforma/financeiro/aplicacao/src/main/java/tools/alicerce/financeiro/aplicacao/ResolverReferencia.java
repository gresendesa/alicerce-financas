package tools.alicerce.financeiro.aplicacao;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.Contexto.ContaContexto;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos.Linha;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Capacidade {@code resolver_referencia}: resolve qualquer id curto do financeiro, de qualquer tipo. */
@Component
public class ResolverReferencia {

    private final RepositorioLancamentos lancamentos;
    private final RepositorioEstrutura estrutura;
    private final LeituraDeLancamentos leitura;
    private final Comprovantes comprovantes;
    private final Retratos retratos;
    private final RepositorioCartoes cartoes;
    private final Faturas faturas;
    private final ListarCompromissos compromissos;
    private final ListarOperacoes operacoes;
    private final Referencias referencias;
    private final ObterContexto contexto;
    private final Enderecos enderecos;
    private final Metas metas;

    ResolverReferencia(RepositorioLancamentos lancamentos, RepositorioEstrutura estrutura, LeituraDeLancamentos leitura,
            Comprovantes comprovantes, Retratos retratos, RepositorioCartoes cartoes, Faturas faturas, ListarCompromissos compromissos,
            ListarOperacoes operacoes, Referencias referencias, ObterContexto contexto, Enderecos enderecos, Metas metas) {
        this.metas = metas;
        this.operacoes = operacoes;
        this.referencias = referencias;
        this.contexto = contexto;
        this.enderecos = enderecos;
        this.compromissos = compromissos;
        this.lancamentos = lancamentos;
        this.estrutura = estrutura;
        this.leitura = leitura;
        this.comprovantes = comprovantes;
        this.retratos = retratos;
        this.cartoes = cartoes;
        this.faturas = faturas;
    }

    public Object resolver(Chamador chamador, String ref) {
        return resolver(chamador, ref, null);
    }

    /**
     * A entidade da referência, de qualquer tipo de {@link TipoReferencia}.
     *
     * @param compararAgora num retrato, traz a diferença para o cálculo de agora
     */
    public Object resolver(Chamador chamador, String ref, Boolean compararAgora) {
        ReferenciaCurta referencia = ler(ref);
        UUID dono = chamador.dono();
        String citada = ref.strip();
        TipoReferencia tipo = TipoReferencia.doPrefixo(referencia.prefixo()).orElseThrow(() -> ErroTipado.validacao("ref",
                "'" + citada + "' não é um id do financeiro. Os prefixos são: t- lançamento, a- conta, f- fatura, k- compromisso, "
                        + "m- meta, n- comprovante, s- retrato e o- operação."));
        // Sem default: um tipo novo de referência sem resolução aqui não compila.
        return switch (tipo) {
            case LANCAMENTO -> executar(chamador, ref);
            case CONTA -> conta(chamador, referencia, citada);
            case FATURA -> estrutura.entidadeDaReferencia(dono, referencia.id()).flatMap(id -> cartoes.faturaPorId(dono, id))
                    .map(f -> faturas.ver(dono, f))
                    .orElseThrow(() -> naoEncontrado("Nenhuma fatura corresponde a '" + citada + "'."));
            case COMPROMISSO -> {
                try {
                    yield compromissos.ver(chamador, referencia.id());
                } catch (ErroTipado inexistente) {
                    throw naoEncontrado("Nenhum compromisso corresponde a '" + citada + "'.");
                }
            }
            case META -> {
                try {
                    yield metas.consultar(dono, referencia.id());
                } catch (ErroTipado inexistente) {
                    throw naoEncontrado("Nenhuma meta corresponde a '" + citada + "'.");
                }
            }
            case COMPROVANTE -> estrutura.entidadeDaReferencia(dono, referencia.id()).flatMap(id -> comprovantes.ver(dono, id))
                    .orElseThrow(() -> naoEncontrado("Nenhum comprovante corresponde a '" + citada + "'."));
            case RETRATO -> estrutura.entidadeDaReferencia(dono, referencia.id())
                    .flatMap(id -> retratos.ver(dono, id, referencia, Boolean.TRUE.equals(compararAgora)))
                    .orElseThrow(() -> naoEncontrado("Nenhum retrato corresponde a '" + citada + "'."));
            case OPERACAO -> {
                try {
                    yield operacoes.ver(chamador, referencia.id());
                } catch (ErroTipado inexistente) {
                    throw naoEncontrado("Nenhuma operação corresponde a '" + citada + "'.");
                }
            }
        };
    }

    /** A conta com os dados do contexto; arquivada, só com o cadastro, porque o contexto não a lista. */
    private ContaVista conta(Chamador chamador, ReferenciaCurta referencia, String citada) {
        Conta conta;
        try {
            conta = referencias.contaParaConsultar(chamador.dono(), referencia.id(), "ref", c -> true);
        } catch (ErroTipado inexistente) {
            throw naoEncontrado("Nenhuma conta corresponde a '" + citada + "'.");
        }
        String url = enderecos.url(referencia);
        Optional<ContaContexto> doContexto = contexto.executar(chamador).contas().stream()
                .filter(c -> referencia.id().equals(c.id())).findFirst();
        if (doContexto.isEmpty()) {
            return new ContaVista(referencia.id(), url, conta.nome(), conta.tipo().codigo(), conta.aliases(), null, conta.moeda(),
                    conta.arquivada(), null, null, null, null, null);
        }
        ContaContexto c = doContexto.get();
        return new ContaVista(c.id(), url, c.nome(), c.tipo(), c.aliases(), c.contaPai(), c.moeda(), false, c.saldo(), c.marco(),
                c.ultimaConferencia(), c.situacaoConferencia(), c.conferenciaVencida());
    }

    private static ErroTipado naoEncontrado(String mensagem) {
        return ErroTipado.de(CodigoErro.NAO_ENCONTRADO, "ref", mensagem);
    }

    public LancamentoVisto executar(Chamador chamador, String ref) {
        UUID dono = chamador.dono();
        ReferenciaCurta referencia = ler(ref);
        if (!referencia.prefixo().equals(TipoReferencia.LANCAMENTO.prefixo())) {
            throw ErroTipado.validacao("ref", "'" + ref.strip() + "' não é o id de um lançamento.");
        }
        Optional<Linha> linha = estrutura.entidadeDaReferencia(dono, referencia.id()).flatMap(id -> lancamentos.linha(dono, id));
        Linha achada = linha.orElseThrow(() -> ErroTipado.de(CodigoErro.NAO_ENCONTRADO, "ref",
                "Nenhum lançamento corresponde a '" + ref.strip() + "'."));
        return leitura.ver(dono, achada, lancamentos.itens(achada.id()))
                .comComprovantes(comprovantes.doLancamento(dono, achada.id()));
    }

    private static ReferenciaCurta ler(String ref) {
        if (Argumentos.vazio(ref)) {
            throw ErroTipado.camposFaltando(java.util.List.of("ref"), "Peça ao dono o id ou a URL.");
        }
        return ReferenciaCurta.ler(ref, TipoReferencia.MODULO)
                .orElseThrow(() -> ErroTipado.validacao("ref", "Use um id curto, como t-8K3QZ2, ou a URL completa."));
    }
}
