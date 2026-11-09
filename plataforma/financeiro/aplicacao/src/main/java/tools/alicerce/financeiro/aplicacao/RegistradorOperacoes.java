package tools.alicerce.financeiro.aplicacao;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.HashArgumentos;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioAuditoria.EventoAuditoria;
import tools.alicerce.financeiro.aplicacao.RepositorioOperacoes.OperacaoGravada;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.jackson.databind.json.JsonMapper;

/**
 * Por onde passa toda escrita. Numa única transação grava a operação (com id curto), roda a escrita,
 * grava os efeitos e versões que ela declara, o resultado e o evento de auditoria. Uma falha desfaz
 * tudo e deixa só o evento de auditoria do erro, gravado depois.
 *
 * <p>Idempotência: a mesma chave com os mesmos argumentos devolve o resultado original com
 * {@code repetido}, sem rodar a escrita de novo; com argumentos diferentes, {@code conflito_idempotencia}.
 * Duas chamadas simultâneas com a mesma chave não gravam duas vezes: a segunda espera a primeira e
 * repete o resultado dela.
 */
@Component
public class RegistradorOperacoes {

    private final RepositorioOperacoes operacoes;
    private final RepositorioAuditoria auditoria;
    private final TransactionTemplate transacao;
    private final Enderecos enderecos;
    private final JsonMapper json = JsonMapper.builder().build();

    RegistradorOperacoes(RepositorioOperacoes operacoes, RepositorioAuditoria auditoria,
            TransactionTemplate transacao, Enderecos enderecos) {
        this.operacoes = operacoes;
        this.auditoria = auditoria;
        this.transacao = transacao;
        this.enderecos = enderecos;
    }

    /**
     * @param tipoResultado a classe do resultado, para devolvê-lo de novo numa repetição
     */
    public <R> ResultadoEscrita<R> registrar(Chamador chamador, PedidoEscrita pedido, Class<R> tipoResultado,
            Escrita<R> escrita) {
        String argumentos = json.writeValueAsString(pedido.argumentos());
        try {
            return Objects.requireNonNull(transacao.execute(status ->
                    executar(chamador, pedido, argumentos, tipoResultado, escrita)));
        } catch (ErroTipado erro) {
            auditoria.registrar(evento(chamador, pedido, argumentos, erro.codigo(), null));
            throw erro;
        } catch (RuntimeException erro) {
            auditoria.registrar(evento(chamador, pedido, argumentos, CodigoErro.ERRO_INTERNO, null));
            throw erro;
        }
    }

    /**
     * Roda a escrita inteira e desfaz tudo no fim: devolve exatamente o que seria gravado, sem gravar.
     * Fica só o evento de auditoria. A chave de idempotência não é usada: simular e gravar são pedidos
     * diferentes.
     */
    public <R> ResultadoEscrita<R> simular(Chamador chamador, PedidoEscrita pedido, Escrita<R> escrita) {
        String argumentos = json.writeValueAsString(pedido.argumentos());
        try {
            R valor = transacao.execute(status -> {
                status.setRollbackOnly();
                return simularNaTransacao(chamador, pedido, argumentos, escrita);
            });
            auditoria.registrar(evento(chamador, pedido, argumentos, null, null));
            return new ResultadoEscrita<>(valor, null, false);
        } catch (ErroTipado erro) {
            auditoria.registrar(evento(chamador, pedido, argumentos, erro.codigo(), null));
            throw erro;
        } catch (RuntimeException erro) {
            auditoria.registrar(evento(chamador, pedido, argumentos, CodigoErro.ERRO_INTERNO, null));
            throw erro;
        }
    }

    /**
     * Uma chamada barrada antes de chegar à escrita, como a tool sem o escopo exigido: fica só o evento
     * de auditoria, sem os argumentos, porque a chamada não foi autorizada.
     */
    public void registrarRecusa(Chamador chamador, String capacidade, CodigoErro erro) {
        auditoria.registrar(new EventoAuditoria(chamador.dono(), chamador.cliente(), chamador.escopos(), capacidade, null, "{}",
                Objects.requireNonNull(erro, "erro"), null));
    }

        private <R> R simularNaTransacao(Chamador chamador, PedidoEscrita pedido, String argumentos, Escrita<R> escrita) {
        UUID id = UUID.randomUUID();
        PedidoEscrita semChave = new PedidoEscrita(pedido.capacidade(), pedido.argumentos(), null, pedido.lote());
        operacoes.inserir(id, chamador.dono(), semChave, argumentos, chamador.cliente(), null);
        ReferenciaCurta referencia = operacoes.reservarReferencia(chamador.dono(), TipoReferencia.OPERACAO, id);
        return escrita.executar(new OperacaoEmCurso(id, chamador.dono(), referencia, operacoes, json));
    }

    private <R> ResultadoEscrita<R> executar(Chamador chamador, PedidoEscrita pedido, String argumentos,
            Class<R> tipoResultado, Escrita<R> escrita) {
        UUID dono = chamador.dono();
        ChaveIdempotencia chave = pedido.chave();
        String hash = chave == null ? null : HashArgumentos.de(pedido.argumentos());
        if (chave != null) {
            var anterior = operacoes.buscarPorChave(dono, chave.valor());
            if (anterior.isPresent()) {
                return repetir(chamador, pedido, argumentos, hash, anterior.get(), tipoResultado);
            }
        }

        UUID id = UUID.randomUUID();
        if (!operacoes.inserir(id, dono, pedido, argumentos, chamador.cliente(), hash)) {
            // Outra chamada com a mesma chave terminou enquanto esta esperava.
            OperacaoGravada vencedora = operacoes.buscarPorChave(dono, chave.valor()).orElseThrow();
            return repetir(chamador, pedido, argumentos, hash, vencedora, tipoResultado);
        }
        ReferenciaCurta referencia = operacoes.reservarReferencia(dono, TipoReferencia.OPERACAO, id);
        OperacaoEmCurso emCurso = new OperacaoEmCurso(id, dono, referencia, operacoes, json);
        R valor = escrita.executar(emCurso);
        emCurso.encerrar();
        if (emCurso.efeitos() == 0) {
            throw new IllegalStateException("A escrita de " + pedido.capacidade() + " não declarou nenhum efeito");
        }
        operacoes.inserirResultado(id, json.writeValueAsString(valor));
        auditoria.registrar(evento(chamador, pedido, argumentos, null, id));
        return new ResultadoEscrita<>(valor, registrada(id, referencia), false);
    }

    private <R> ResultadoEscrita<R> repetir(Chamador chamador, PedidoEscrita pedido, String argumentos, String hash,
            OperacaoGravada anterior, Class<R> tipoResultado) {
        if (!anterior.capacidade().equals(pedido.capacidade()) || !anterior.hashArgumentos().equals(hash)) {
            throw ErroTipado.de(CodigoErro.CONFLITO_IDEMPOTENCIA, ChaveIdempotencia.CAMPO,
                    "A chave_idempotencia já foi usada numa chamada com outros argumentos. "
                            + "Gere uma chave nova para um pedido novo; repita a mesma só para repetir a mesma chamada.");
        }
        String resultado = operacoes.resultado(anterior.id())
                .orElseThrow(() -> new IllegalStateException("Operação sem resultado: " + anterior.id()));
        ReferenciaCurta referencia = operacoes.reservarReferencia(chamador.dono(), TipoReferencia.OPERACAO, anterior.id());
        auditoria.registrar(evento(chamador, pedido, argumentos, null, anterior.id()));
        return new ResultadoEscrita<>(json.readValue(resultado, tipoResultado), registrada(anterior.id(), referencia), true);
    }

    private OperacaoRegistrada registrada(UUID id, ReferenciaCurta referencia) {
        return new OperacaoRegistrada(id, referencia, enderecos.url(referencia));
    }

    private static EventoAuditoria evento(Chamador chamador, PedidoEscrita pedido, String argumentos,
            CodigoErro erro, UUID operacao) {
        return new EventoAuditoria(chamador.dono(), chamador.cliente(), chamador.escopos(), pedido.capacidade(),
                pedido.chave() == null ? null : pedido.chave().valor(), argumentos, erro, operacao);
    }
}
