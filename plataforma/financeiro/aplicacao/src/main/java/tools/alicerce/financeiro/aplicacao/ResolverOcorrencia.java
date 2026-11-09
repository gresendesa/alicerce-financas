package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Datas;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CalculoCompromisso;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.DirecaoCompromisso;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.Ocorrencia;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;
import tools.alicerce.financeiro.dominio.TipoLancamento;

/**
 * Capacidade {@code resolver_ocorrencia}: pula ou adia uma ocorrência, ou a vincula a um lançamento já
 * registrado, sem lançamento novo. Desfazer devolve a ocorrência ao que era.
 */
@Component
public class ResolverOcorrencia {

    public record Entrada(String chaveIdempotencia, String lote, String compromisso, String ocorrencia, String acao, String novaData,
            String lancamento, String observacao) {}

    static final String CAPACIDADE = "resolver_ocorrencia";

    private final RegistradorOperacoes registrador;
    private final RepositorioCompromissos repositorio;
    private final RepositorioLancamentos lancamentos;
    private final Compromissos compromissos;
    private final Referencias referencias;

    ResolverOcorrencia(RegistradorOperacoes registrador, RepositorioCompromissos repositorio, RepositorioLancamentos lancamentos,
            Compromissos compromissos, Referencias referencias) {
        this.registrador = registrador;
        this.repositorio = repositorio;
        this.lancamentos = lancamentos;
        this.compromissos = compromissos;
        this.referencias = referencias;
    }

    public ResultadoEscrita<OcorrenciaResolvida> executar(Chamador chamador, Entrada e) {
        Map<String, ?> argumentos = new Argumentos().com("compromisso", e.compromisso()).com("ocorrencia", e.ocorrencia()).com("acao", e.acao())
                .com("nova_data", e.novaData()).com("lancamento", e.lancamento()).com("observacao", e.observacao()).mapa();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, ChaveIdempotencia.ler(e.chaveIdempotencia()), e.lote());
        return registrador.registrar(chamador, pedido, OcorrenciaResolvida.class, operacao -> resolver(operacao, e));
    }

    private OcorrenciaResolvida resolver(OperacaoEmCurso operacao, Entrada e) {
        List<String> faltando = new ArrayList<>();
        if (Argumentos.vazio(e.compromisso())) {
            faltando.add("compromisso");
        }
        if (Argumentos.vazio(e.acao())) {
            faltando.add("acao");
        }
        String acao = Argumentos.vazio(e.acao()) ? "" : e.acao().strip();
        if (acao.equals("adiar") && Argumentos.vazio(e.novaData())) {
            faltando.add("nova_data");
        }
        if (acao.equals("vincular") && Argumentos.vazio(e.lancamento())) {
            faltando.add("lancamento");
        }
        if (!faltando.isEmpty()) {
            throw ErroTipado.camposFaltando(faltando, "Pergunte ao dono qual compromisso e o que fazer: pular, adiar (para quando) ou vincular a um lançamento.");
        }
        UUID dono = operacao.dono();
        LocalDate hoje = compromissos.hoje(dono);
        Compromisso c = compromissos.resolver(dono, e.compromisso(), "compromisso");
        return switch (acao) {
            case "vincular" -> vincular(operacao, c, e, hoje);
            case "pular", "adiar" -> pularOuAdiar(operacao, c, e, acao, hoje);
            default -> throw ErroTipado.validacao("acao", "Ação inválida: use pular, adiar ou vincular.");
        };
    }

    private OcorrenciaResolvida pularOuAdiar(OperacaoEmCurso operacao, Compromisso c, Entrada e, String acao, LocalDate hoje) {
        UUID dono = operacao.dono();
        Map<Integer, List<RegistroOcorrencia>> registros = repositorio.registros(c.id());
        int numero = compromissos.escolher(dono, c, registros, e.ocorrencia(), hoje, hoje);
        Ocorrencia atual = CalculoCompromisso.ocorrencia(c, numero, registros.get(numero), hoje);
        if (atual.situacao().paga()) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "ocorrencia", "A ocorrência " + Compromissos.parcela(c, numero) + " de '" + c.nome()
                    + "' já está paga. Para reabri-la, anule ou desfaça o pagamento.");
        }
        String observacao = Argumentos.vazio(e.observacao()) ? null : e.observacao().strip();
        if (acao.equals("pular")) {
            compromissos.gravar(operacao, c, numero, RegistroOcorrencia.Situacao.PULADA, null, null, observacao);
        } else {
            LocalDate nova = Datas.ler(e.novaData().strip(), "nova_data");
            if (!nova.isAfter(atual.vencimentoOriginal())) {
                throw ErroTipado.validacao("nova_data", "Adiar é para depois do vencimento original (" + atual.vencimentoOriginal() + ").");
            }
            compromissos.gravar(operacao, c, numero, RegistroOcorrencia.Situacao.ADIADA, null, nova, observacao);
        }
        return saida(dono, c, numero, acao, hoje);
    }

    private OcorrenciaResolvida vincular(OperacaoEmCurso operacao, Compromisso c, Entrada e, LocalDate hoje) {
        UUID dono = operacao.dono();
        UUID id = referencias.lancamento(dono, e.lancamento(), "lancamento");
        Lancamento l = lancamentos.buscar(dono, id).orElseThrow();
        TipoLancamento esperado = c.direcao() == DirecaoCompromisso.SAIDA ? TipoLancamento.GASTO : TipoLancamento.GANHO;
        if (l.tipo() != esperado) {
            throw ErroTipado.validacao("lancamento", "Um compromisso de " + c.direcao().codigo() + " só se paga com um "
                    + esperado.name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        if (lancamentos.cadeia(dono, id).tamanho() % 2 == 1) {
            throw ErroTipado.de(CodigoErro.VIOLACAO_INVARIANTE, "lancamento", "Esse lançamento foi anulado.");
        }
        if (!compromissos.pagamentosDe(id).isEmpty()) {
            throw ErroTipado.de(CodigoErro.CONFLITO, "lancamento", "Esse lançamento já paga uma ocorrência. Um pagamento quita uma ocorrência só.");
        }
        OcorrenciaVinculada vinculada = compromissos.vincular(operacao, e.compromisso(), e.ocorrencia(), l, c.direcao());
        return saida(dono, c, vinculada.numero(), "vincular", hoje);
    }

    private OcorrenciaResolvida saida(UUID dono, Compromisso c, int numero, String acao, LocalDate hoje) {
        Map<Integer, List<RegistroOcorrencia>> registros = repositorio.registros(c.id());
        Ocorrencia o = CalculoCompromisso.ocorrencia(c, numero, registros.get(numero), hoje);
        CompromissoVisto visto = compromissos.ver(dono, c, registros, hoje);
        return new OcorrenciaResolvida(visto.id(), visto.url(), c.nome(), acao, compromissos.vistas(c, List.of(o)).get(0), visto.saldo());
    }
}
