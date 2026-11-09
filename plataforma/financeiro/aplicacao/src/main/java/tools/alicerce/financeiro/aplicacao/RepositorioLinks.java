package tools.alicerce.financeiro.aplicacao;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência dos links de prestação de contas. Só o hash do token chega aqui. Nada se apaga: a
 * revogação é a única mudança, e a validade sai do estado das operações que criaram e revogaram o link.
 */
public interface RepositorioLinks {

    /**
     * Um link como a leitura o vê.
     *
     * @param criacaoAplicada a operação que criou o link não foi desfeita
     * @param revogado há uma revogação cuja operação não foi desfeita
     */
    record Link(UUID id, UUID dono, UUID retrato, boolean incluirComprovantes, OffsetDateTime expiraEm, boolean criacaoAplicada,
            boolean revogado) {

        /** Abre agora? Vencido, revogado ou de operação desfeita, responde como inexistente. */
        public boolean abre(OffsetDateTime agora) {
            return criacaoAplicada && !revogado && agora.isBefore(expiraEm);
        }
    }

    void inserir(OperacaoEmCurso operacao, UUID id, String hashToken, UUID retrato, boolean incluirComprovantes, OffsetDateTime expiraEm);

    Optional<Link> porHash(String hashToken);

    Optional<Link> doRetrato(UUID dono, UUID retrato);

    void revogar(OperacaoEmCurso operacao, UUID link, OffsetDateTime agora);
}
