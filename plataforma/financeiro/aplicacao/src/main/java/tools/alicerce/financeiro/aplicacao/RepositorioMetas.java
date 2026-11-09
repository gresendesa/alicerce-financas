package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.Meta;

/**
 * Porta de persistência das metas e dos aportes marcados com elas. Um aporte só conta se o lançamento está vigente: anular
 * ou desfazer o aporte tira o valor do progresso.
 */
public interface RepositorioMetas {

    /**
     * Um aporte ou resgate vigente.
     *
     * @param centavos o valor transferido, na moeda da meta, sem sinal
     */
    record Aporte(UUID lancamento, String codigo, LocalDate data, String descricao, long centavos, boolean resgate) {}

    /** Todas as metas do dono, arquivadas inclusive. */
    List<Meta> metas(UUID dono);

    void inserir(OperacaoEmCurso operacao, Meta meta);

    /** Grava o estado novo; a versão que chega já é a nova. */
    void atualizar(OperacaoEmCurso operacao, Meta meta);

    /** Por meta com algum aporte vigente: aportes menos resgates, na moeda da meta. */
    Map<UUID, Long> progressos(UUID dono);

    /** Os aportes e resgates vigentes da meta, do mais antigo ao mais recente. */
    List<Aporte> aportes(UUID meta);

    /** As operações que gravaram aportes ou resgates ainda vigentes. */
    Set<UUID> operacoesComAportes(UUID meta);

    /** Os códigos curtos das metas do dono. */
    Map<UUID, String> referencias(UUID dono);

    default Optional<Meta> meta(UUID dono, UUID id) {
        return metas(dono).stream().filter(m -> m.id().equals(id)).findFirst();
    }
}
