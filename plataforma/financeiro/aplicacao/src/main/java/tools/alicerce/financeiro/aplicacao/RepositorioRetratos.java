package tools.alicerce.financeiro.aplicacao;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistência dos retratos: só inserção e leitura, porque um retrato não muda. */
public interface RepositorioRetratos {

    /** Um retrato; {@code parametros} e {@code resultado} em JSON. */
    record Retrato(UUID id, String capacidade, String parametros, OffsetDateTime dataReferencia, String resultado, String cliente) {}

    void inserir(OperacaoEmCurso operacao, Retrato retrato);

    Optional<Retrato> buscar(UUID dono, UUID id);
}
