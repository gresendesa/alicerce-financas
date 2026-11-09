package tools.alicerce.financeiro.aplicacao;

import java.util.Optional;
import java.util.UUID;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Porta de persistência do registrador de operações. Só o {@link RegistradorOperacoes} e a
 * {@link OperacaoEmCurso} a usam (o teste de arquitetura confere).
 */
public interface RepositorioOperacoes {

    /** Uma operação gravada, como a idempotência a consulta. */
    record OperacaoGravada(UUID id, String capacidade, String hashArgumentos) {}

    /**
     * Grava a operação. Falso se outra com a mesma chave de idempotência já existe; se ela ainda
     * está em andamento, espera a transação dela terminar.
     */
    boolean inserir(UUID id, UUID dono, PedidoEscrita pedido, String argumentosJson, String cliente, String hashArgumentos);

    Optional<OperacaoGravada> buscarPorChave(UUID dono, String chave);

    void inserirEfeito(UUID operacao, int ordem, TipoEfeito tipo, String entidade, UUID entidadeId, Long versaoAnterior,
            String detalhe);

    void marcarDesfeita(UUID operacao, UUID desfeitaPor);

    void marcarAplicada(UUID operacao);

    void inserirVersao(EntidadeVersionada entidade, UUID entidadeId, long versao, String dadosJson, UUID operacao);

    void inserirResultado(UUID operacao, String resultadoJson);

    Optional<String> resultado(UUID operacao);

    /** O endereço curto da entidade, sorteado e gravado agora se ainda não existir. */
    ReferenciaCurta reservarReferencia(UUID dono, TipoReferencia tipo, UUID entidadeId);
}
