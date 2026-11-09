package tools.alicerce.financeiro.aplicacao;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência do índice de comprovantes e dos tokens de envio. */
public interface RepositorioComprovantes {

    /** O índice de um arquivo. {@code natureza} é {@code comprovante} ou {@code foto}. */
    record Indice(UUID id, UUID lancamento, String tipoMidia, int tamanhoBytes, String chave, String sha256,
            OffsetDateTime enviadoEm, String natureza) {}

    /** Um link de envio; {@code emitidoPor} é o cliente da operação que o emitiu. */
    record TokenAnexo(UUID dono, UUID lancamento, OffsetDateTime expiraEm, OffsetDateTime usadoEm, String emitidoPor) {}

    void inserir(OperacaoEmCurso operacao, Indice indice);

    void vincular(OperacaoEmCurso operacao, UUID comprovante, UUID lancamento);

    Optional<Indice> buscar(UUID dono, UUID id);

    List<Indice> doLancamento(UUID dono, UUID lancamento);

    void inserirToken(OperacaoEmCurso operacao, UUID id, String hash, UUID lancamento, OffsetDateTime expiraEm);

    Optional<TokenAnexo> token(String hash);

    /** Marca o token como usado. Falso se já foi usado ou venceu: duas chamadas juntas não passam as duas. */
    boolean consumirToken(OperacaoEmCurso operacao, String hash, OffsetDateTime agora);
}
