package tools.alicerce.financeiro.aplicacao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Efeito;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Operacao;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada.LancamentoRevertido;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.seguranca.EscoposDasFerramentas;

/**
 * Capacidade {@code desfazer}, em dois passos. A prévia roda a reversão inteira e desfaz a transação no
 * fim, então mostra exatamente o que vai acontecer e pega os bloqueios sem gravar nada. A confirmação
 * roda a mesma coisa de verdade e confere que o resultado é o que a prévia mostrou.
 *
 * <p>O escopo exigido depende do alvo, por isso é conferido aqui: a tool só exige {@code fin:read}. O
 * escopo {@link #ESCOPO_PROPRIO}, da interface web, supre os escopos de {@link #ALCANCE_DO_ESCOPO_PROPRIO}
 * só neste caso de uso. A lista é fixa: escopo novo só entra por decisão de projeto.
 */
@Component
public class Desfazer {

    /** O escopo só de desfazer, opcional no cliente {@code app-web}. */
    public static final String ESCOPO_PROPRIO = "fin:undo";

    private static final Set<String> ALCANCE_DO_ESCOPO_PROPRIO = Set.of("fin:write", "fin:structure");

    public record Entrada(String chaveIdempotencia, String alvo, String lote, String confirmacao) {}

    private record Reversao(ReversaoRegistrada saida, String impressao) {}

    private static final String CAPACIDADE = "desfazer";
    private static final String PROXIMO_PASSO = "Mostre esta prévia ao dono. Depois do sim, chame desfazer de novo com o mesmo alvo ou lote, "
            + "este token em confirmacao e uma chave_idempotencia nova. Não peça outra prévia: é a segunda chamada que aplica.";

    private final RegistradorOperacoes registrador;
    private final RepositorioHistorico historico;
    private final RepositorioLancamentos lancamentos;
    private final Referencias referencias;
    private final Inversos inversos;
    private final RegistroDeLancamento registro;
    private final ResumoOperacao resumo;
    private final Enderecos enderecos;
    private final ConfirmacoesDesfazer confirmacoes;
    private final EscoposDasFerramentas escopos;
    private final Declaracoes declaracoes;
    private final RepositorioDono donos;

    Desfazer(RegistradorOperacoes registrador, RepositorioHistorico historico, RepositorioLancamentos lancamentos,
            Referencias referencias, Inversos inversos, RegistroDeLancamento registro, ResumoOperacao resumo,
            Enderecos enderecos, ConfirmacoesDesfazer confirmacoes, EscoposDasFerramentas escopos, Declaracoes declaracoes,
            RepositorioDono donos) {
        this.declaracoes = declaracoes;
        this.donos = donos;
        this.registrador = registrador;
        this.historico = historico;
        this.lancamentos = lancamentos;
        this.referencias = referencias;
        this.inversos = inversos;
        this.registro = registro;
        this.resumo = resumo;
        this.enderecos = enderecos;
        this.confirmacoes = confirmacoes;
        this.escopos = escopos;
    }

    public ResultadoEscrita<ReversaoRegistrada> executar(Chamador chamador, Entrada e) {
        // O token fica fora dos argumentos: repetir com a mesma chave depois de uma nova prévia é a mesma intenção.
        Map<String, ?> argumentos = new Argumentos().com("alvo", e.alvo()).com("lote", e.lote()).mapa();
        if (Argumentos.vazio(e.confirmacao())) {
            PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, null, null);
            ResultadoEscrita<Reversao> previa = registrador.simular(chamador, pedido, o -> reverter(o, chamador, e));
            String token = confirmacoes.emitir(chamador.dono(), previa.valor().impressao());
            String validaAte = confirmacoes.validoAte().toString();
            return new ResultadoEscrita<>(previa.valor().saida().comConfirmacao(token, validaAte, PROXIMO_PASSO), null, false);
        }
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, ChaveIdempotencia.ler(e.chaveIdempotencia()), null);
        return registrador.registrar(chamador, pedido, ReversaoRegistrada.class, o -> {
            String esperada = confirmacoes.conferir(o.dono(), e.confirmacao());
            Reversao reversao = reverter(o, chamador, e);
            if (!reversao.impressao().equals(esperada)) {
                throw ConfirmacoesDesfazer.invalida();
            }
            return reversao.saida();
        });
    }

    private Reversao reverter(OperacaoEmCurso operacao, Chamador chamador, Entrada e) {
        List<Operacao> alvos = alvos(operacao.dono(), e);
        exigirEscopos(chamador, alvos);
        ReversaoEmCurso r = new ReversaoEmCurso();
        List<OperacaoVista> vistas = new ArrayList<>();
        // A declaração não tem inverso próprio: a vigente muda porque a operação dela deixa de valer. Comparar antes e
        // depois mostra ao dono qual sai e qual volta, também ao desfazer um desfazer.
        Map<TipoDeclaracao, Declaracoes.Vigente> declaracoesAntes = declaracoes.vigentes(operacao.dono());
        for (Operacao alvo : alvos) {
            r.impressao.add("alvo " + alvo.id());
            List<Efeito> efeitos = new ArrayList<>(historico.efeitos(alvo.id()));
            Collections.reverse(efeitos);
            r.chavesDoAlvo.clear();
            efeitos.stream().filter(f -> f.tipo() == TipoEfeito.CHAVE_FISCAL_MOVIDA).map(Efeito::detalhe).forEach(r.chavesDoAlvo::add);
            for (Efeito efeito : efeitos) {
                inversos.aplicar(operacao, alvo, efeito, r);
            }
            if (r.dependentes.isEmpty()) {
                operacao.operacaoDesfeita(alvo.id());
            }
            vistas.add(resumo.ver(alvo));
        }
        inversos.conferirFaturas(operacao, r);
        if (!r.dependentes.isEmpty()) {
            throw bloqueado(operacao.dono(), r.dependentes, alvos);
        }
        List<ReversaoRegistrada.DeclaracaoRevertida> revertidas = declaracoesRevertidas(operacao.dono(), declaracoesAntes);
        return new Reversao(saida(operacao, r, vistas).comDeclaracoes(revertidas), ConfirmacoesDesfazer.impressao(r.impressao));
    }

    private List<ReversaoRegistrada.DeclaracaoRevertida> declaracoesRevertidas(UUID dono, Map<TipoDeclaracao, Declaracoes.Vigente> antes) {
        Map<TipoDeclaracao, Declaracoes.Vigente> depois = declaracoes.vigentes(dono);
        java.util.Currency moeda = donos.buscar(dono).orElseThrow().moeda();
        List<ReversaoRegistrada.DeclaracaoRevertida> lista = new ArrayList<>();
        for (TipoDeclaracao tipo : TipoDeclaracao.values()) {
            Declaracoes.Vigente a = antes.get(tipo);
            Declaracoes.Vigente d = depois.get(tipo);
            UUID idAntes = a == null ? null : a.declaracao().id();
            UUID idDepois = d == null ? null : d.declaracao().id();
            if (!java.util.Objects.equals(idAntes, idDepois)) {
                lista.add(new ReversaoRegistrada.DeclaracaoRevertida(tipo.codigo(), a == null ? null : declaracoes.vista(dono, a, moeda),
                        d == null ? null : declaracoes.vista(dono, d, moeda)));
            }
        }
        return lista;
    }

    /** Da última para a primeira. Num lote, o que já foi desfeito fica de fora. */
    private List<Operacao> alvos(UUID dono, Entrada e) {
        boolean porOperacao = !Argumentos.vazio(e.alvo());
        boolean porLote = !Argumentos.vazio(e.lote());
        if (porOperacao == porLote) {
            if (porOperacao) {
                throw ErroTipado.validacao("alvo", "Informe alvo ou lote, não os dois.");
            }
            throw ErroTipado.camposFaltando(List.of("alvo"), "Pergunte ao dono o que desfazer; listar_operacoes mostra o histórico.");
        }
        if (porOperacao) {
            UUID id = referencias.operacao(dono, e.alvo(), "alvo");
            Operacao alvo = historico.travar(dono, id).orElseThrow();
            if (alvo.desfeita()) {
                throw jaDesfeita(dono, alvo);
            }
            return List.of(alvo);
        }
        List<Operacao> doLote = historico.travarLote(dono, e.lote().strip());
        if (doLote.isEmpty()) {
            throw ErroTipado.de(CodigoErro.NAO_ENCONTRADO, "lote", "Nenhuma operação no lote '" + e.lote().strip() + "'.");
        }
        List<Operacao> aplicadas = new ArrayList<>(doLote.stream().filter(o -> !o.desfeita()).toList());
        if (aplicadas.isEmpty()) {
            throw jaDesfeita(dono, doLote.get(doLote.size() - 1));
        }
        Collections.reverse(aplicadas);
        return aplicadas;
    }

    private ErroTipado jaDesfeita(UUID dono, Operacao alvo) {
        List<ErroTipado.Candidato> candidatos = Optional.ofNullable(alvo.desfeitaPor())
                .flatMap(id -> historico.buscar(dono, id)).map(this::candidato).map(List::of).orElse(List.of());
        return ErroTipado.referencia(CodigoErro.JA_DESFEITA, "alvo", "Essa operação já foi desfeita.", candidatos,
                "Para refazer, desfaça a operação de desfazer.");
    }

    private void exigirEscopos(Chamador chamador, List<Operacao> alvos) {
        Set<String> exigidos = new LinkedHashSet<>();
        for (Operacao alvo : alvos) {
            escoposDe(alvo, chamador.dono(), exigidos);
        }
        exigidos.removeAll(chamador.escopos());
        if (chamador.escopos().contains(ESCOPO_PROPRIO)) {
            exigidos.removeAll(ALCANCE_DO_ESCOPO_PROPRIO);
        }
        if (!exigidos.isEmpty()) {
            throw ErroTipado.de(CodigoErro.INSUFFICIENT_SCOPE,
                    "Desfazer isso exige os mesmos escopos da operação original. Falta: " + String.join(" ", exigidos) + ".");
        }
    }

    /** Os escopos da capacidade; um desfazer exige os das operações que ele reverteu. */
    private void escoposDe(Operacao operacao, UUID dono, Set<String> exigidos) {
        if (operacao.capacidade().equals(EnviarComprovante.CAPACIDADE)) {
            exigidos.add(EnviarComprovante.ESCOPO_DESFAZER); // veio da página, que não é uma tool
            return;
        }
        if (!operacao.capacidade().equals(CAPACIDADE)) {
            escopos.escopoDe(operacao.capacidade()).ifPresent(exigidos::add);
            return;
        }
        for (Efeito efeito : historico.efeitos(operacao.id())) {
            if (efeito.tipo() == TipoEfeito.OPERACAO_DESFEITA || efeito.tipo() == TipoEfeito.OPERACAO_RESTABELECIDA) {
                escoposDe(historico.buscar(dono, efeito.entidadeId()).orElseThrow(), dono, exigidos);
            }
        }
    }

    private ErroTipado bloqueado(UUID dono, Set<UUID> dependentes, List<Operacao> alvos) {
        Set<UUID> ids = new LinkedHashSet<>(dependentes);
        alvos.forEach(a -> ids.remove(a.id()));
        List<ErroTipado.Candidato> candidatos = ids.stream().map(id -> historico.buscar(dono, id)).flatMap(Optional::stream)
                .map(this::candidato).toList();
        return ErroTipado.referencia(CodigoErro.DESFAZER_BLOQUEADO, "alvo",
                "Operações feitas depois dependem desta. Para desfazer, é preciso desfazer essas antes.", candidatos,
                "Mostre ao dono as dependentes e proponha desfazer o conjunto.");
    }

    private ErroTipado.Candidato candidato(Operacao o) {
        OperacaoVista vista = resumo.ver(o);
        return new ErroTipado.Candidato(vista.id(), vista.resumo(), vista.url());
    }

    private ReversaoRegistrada saida(OperacaoEmCurso operacao, ReversaoEmCurso r, List<OperacaoVista> vistas) {
        List<LancamentoRevertido> estornados = new ArrayList<>();
        List<LancamentoRevertido> restabelecidos = new ArrayList<>();
        r.lancamentos.forEach((id, volta) -> (volta ? restabelecidos : estornados).add(revertido(operacao, id)));
        return new ReversaoRegistrada(vistas, estornados, restabelecidos, r.restauradas, r.arquivadas,
                r.mantidas.stream().distinct().toList(), registro.saldos(operacao.dono(), r.contasTocadas), r.externos.stream().distinct().toList(), null, null, null, List.of());
    }

    private LancamentoRevertido revertido(OperacaoEmCurso operacao, UUID id) {
        Lancamento l = lancamentos.buscar(operacao.dono(), id).orElseThrow();
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.LANCAMENTO, id);
        return new LancamentoRevertido(referencia.id(), enderecos.url(referencia), l.descricao(), l.total().texto(), l.total().moeda().getCurrencyCode());
    }
}
