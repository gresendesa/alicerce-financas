package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;

/** Porta de persistência das declarações do perfil: só inserção. Só valem as de operação aplicada. */
public interface RepositorioDeclaracoes {

    /**
     * Uma declaração gravada.
     *
     * @param valor o JSON do tipo, como {@link Declaracoes} o escreve
     * @param operacao o id curto da operação que a gravou; nulo na inserção
     */
    record Declaracao(UUID id, TipoDeclaracao tipo, String valor, LocalDate vigenteDesde, String observacao, String operacao,
            OffsetDateTime registradaEm) {}

    void inserir(OperacaoEmCurso operacao, Declaracao declaracao);

    /** As declarações que valem do dono, da última gravada para a primeira. A primeira de cada tipo é a vigente. */
    List<Declaracao> declaracoes(UUID dono);
}
