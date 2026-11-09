package tools.alicerce.financeiro.aplicacao;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura do histórico de escritas, para listar e desfazer. Só lê: gravar operação, efeito ou versão
 * continua sendo do registrador.
 */
public interface RepositorioHistorico {

    /** Uma operação gravada. {@code resultado} é o JSON devolvido ao assistente. */
    record Operacao(UUID id, String codigo, String capacidade, String argumentos, String resultado, String lote,
            String cliente, boolean desfeita, UUID desfeitaPor, OffsetDateTime criadaEm) {}

    record Efeito(int ordem, TipoEfeito tipo, String entidade, UUID entidadeId, Long versaoAnterior, String detalhe) {}

    /** O estado completo de uma entidade numa versão, e a operação que o gravou. */
    record Versao(long versao, String dados, UUID operacao) {}

    /** Onde um cursor de operações parou. */
    record Posicao(OffsetDateTime criadaEm, UUID id) {}

    /**
     * Filtros de {@code listar_operacoes}, já resolvidos; nulo não filtra.
     *
     * @param semCapacidades ficam de fora; vazio não exclui nada
     */
    record Filtro(String lote, String cliente, OffsetDateTime de, OffsetDateTime ate, String capacidade,
            java.util.Set<String> semCapacidades, boolean incluirDesfeitas, Posicao depoisDe) {}

    Optional<Operacao> buscar(UUID dono, UUID id);

    /** A operação, travada até o fim da transação: dois desfazer do mesmo alvo não correm juntos. */
    Optional<Operacao> travar(UUID dono, UUID id);

    /** As operações do lote, da mais antiga para a mais recente, travadas. */
    List<Operacao> travarLote(UUID dono, String lote);

    /** Os efeitos da operação, em ordem. */
    List<Efeito> efeitos(UUID operacao);

    Optional<Versao> versao(EntidadeVersionada entidade, UUID id, long versao);

    Optional<Versao> ultimaVersao(EntidadeVersionada entidade, UUID id);

    /** As notas que já saíram deste lançamento para outro. */
    List<String> chavesQueSairamDe(UUID lancamento);

    /** A última operação que mudou o vínculo do comprovante. */
    Optional<UUID> ultimoVinculo(UUID comprovante);

    /** Até {@code limite} operações, das mais recentes às mais antigas. */
    List<Operacao> listar(UUID dono, Filtro filtro, int limite);

    /** Uma chamada recusada, gravada em {@code evento_auditoria}: não mudou nada, e não tem id curto. */
    record Recusa(long id, String capacidade, String cliente, String codigoErro, OffsetDateTime ocorridaEm) {}

    /** Onde um cursor de recusas parou. */
    record PosicaoRecusa(OffsetDateTime ocorridaEm, long id) {}

    /** Filtros das recusas, já resolvidos; nulo não filtra. */
    record FiltroRecusas(String cliente, OffsetDateTime de, OffsetDateTime ate, java.util.Set<String> semCapacidades,
            PosicaoRecusa depoisDe) {}

    /** Até {@code limite} recusas, das mais recentes às mais antigas. */
    List<Recusa> recusas(UUID dono, FiltroRecusas filtro, int limite);

    /** Os clientes que já escreveram ou tentaram escrever para o dono, em ordem alfabética. */
    List<String> clientes(UUID dono);

    /**
     * Um efeito com o id curto do registro afetado; {@code referencia} é nula quando o registro não tem
     * endereço. O estorno aponta para o lançamento estornado, que é o que o dono reconhece.
     */
    record EfeitoReferenciado(TipoEfeito tipo, String entidade, String referencia) {}

    List<EfeitoReferenciado> efeitosReferenciados(UUID operacao);

    /**
     * Os ids curtos das operações que criaram ou mudaram o registro do dono, da mais antiga para a mais
     * recente. Num lançamento, contam também os estornos que apontam para ele.
     */
    List<String> operacoesDoRegistro(UUID dono, String referencia);
}
