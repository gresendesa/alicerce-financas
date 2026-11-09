package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.Compromisso;
import tools.alicerce.financeiro.dominio.RegistroOcorrencia;

/**
 * Porta de persistência dos compromissos e das ocorrências gravadas. Uma ocorrência só vale se a operação
 * que a gravou está aplicada e, se é pagamento, se o lançamento está vigente. Um número pode ter várias
 * gravações (pagamentos parciais, adiamento, pulo); o estado dele sai de todas, no domínio.
 */
public interface RepositorioCompromissos {

    /** Uma ocorrência a gravar. @param vencimento o vencimento pela regra, para consulta */
    record OcorrenciaGravada(UUID id, UUID compromisso, int numero, LocalDate vencimento, RegistroOcorrencia.Situacao situacao,
            UUID lancamento, LocalDate novaData, String observacao) {}

    /** Uma ocorrência paga por um lançamento vigente. */
    record Pagamento(UUID compromisso, int numero, LocalDate vencimento, String observacao) {}

    List<Compromisso> compromissos(UUID dono);

    void inserir(OperacaoEmCurso operacao, Compromisso compromisso);

    /** Grava o estado novo; a versão que chega já é a nova. */
    void atualizar(OperacaoEmCurso operacao, Compromisso compromisso);

    /** As gravações de ocorrência que valem, por número, na ordem em que foram feitas. */
    Map<Integer, List<RegistroOcorrencia>> registros(UUID compromisso);

    /** As gravações de ocorrência que valem de todos os compromissos do dono, por número e em ordem. */
    Map<UUID, Map<Integer, List<RegistroOcorrencia>>> registrosDoDono(UUID dono);

    void inserirOcorrencia(OperacaoEmCurso operacao, OcorrenciaGravada ocorrencia);

    /** As ocorrências que o lançamento paga, se ele ainda as paga. */
    List<Pagamento> pagamentosDoLancamento(UUID lancamento);

    /** As operações que gravaram ocorrências que ainda valem. */
    Set<UUID> operacoesComOcorrencias(UUID compromisso);

    /** Os códigos curtos dos compromissos do dono. */
    Map<UUID, String> referencias(UUID dono);

    default Optional<Compromisso> compromisso(UUID dono, UUID id) {
        return compromissos(dono).stream().filter(c -> c.id().equals(id)).findFirst();
    }
}
