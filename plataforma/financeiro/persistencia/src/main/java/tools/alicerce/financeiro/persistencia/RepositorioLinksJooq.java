package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.LINK_COMPARTILHAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioLinks;

/**
 * Persistência dos links de prestação de contas: INSERT e, na revogação, UPDATE só de {@code revogado_em} e
 * {@code revogado_por}. Criado ou revogado por operação desfeita não conta, sem inverso que mude a linha.
 */
@Repository
class RepositorioLinksJooq implements RepositorioLinks {

    private final DSLContext banco;

    RepositorioLinksJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, UUID id, String hashToken, UUID retrato, boolean incluirComprovantes,
            OffsetDateTime expiraEm) {
        banco.insertInto(LINK_COMPARTILHAMENTO).set(LINK_COMPARTILHAMENTO.ID, id).set(LINK_COMPARTILHAMENTO.HASH_TOKEN, hashToken)
                .set(LINK_COMPARTILHAMENTO.DONO_ID, operacao.dono()).set(LINK_COMPARTILHAMENTO.RETRATO_ID, retrato)
                .set(LINK_COMPARTILHAMENTO.INCLUIR_COMPROVANTES, incluirComprovantes).set(LINK_COMPARTILHAMENTO.EXPIRA_EM, expiraEm)
                .set(LINK_COMPARTILHAMENTO.OPERACAO_ID, operacao.id()).execute();
    }

    @Override
    public Optional<Link> porHash(String hashToken) {
        return buscar(LINK_COMPARTILHAMENTO.HASH_TOKEN.eq(hashToken));
    }

    @Override
    public Optional<Link> doRetrato(UUID dono, UUID retrato) {
        return buscar(LINK_COMPARTILHAMENTO.DONO_ID.eq(dono).and(LINK_COMPARTILHAMENTO.RETRATO_ID.eq(retrato)));
    }

    @Override
    public void revogar(OperacaoEmCurso operacao, UUID link, OffsetDateTime agora) {
        banco.update(LINK_COMPARTILHAMENTO).set(LINK_COMPARTILHAMENTO.REVOGADO_EM, agora).set(LINK_COMPARTILHAMENTO.REVOGADO_POR, operacao.id())
                .where(LINK_COMPARTILHAMENTO.ID.eq(link), LINK_COMPARTILHAMENTO.DONO_ID.eq(operacao.dono()))
                .execute();
    }

    private Optional<Link> buscar(Condition condicao) {
        var criacao = OPERACAO.as("criacao");
        var revogacao = OPERACAO.as("revogacao");
        Field<Boolean> criacaoAplicada = DSL.field(criacao.ESTADO.eq("aplicada"));
        // Sem revogação, o lado da junção é nulo: Boolean.TRUE.equals descarta.
        Field<Boolean> revogado = DSL.field(revogacao.ESTADO.eq("aplicada"));
        return banco.select(LINK_COMPARTILHAMENTO.ID, LINK_COMPARTILHAMENTO.DONO_ID, LINK_COMPARTILHAMENTO.RETRATO_ID,
                        LINK_COMPARTILHAMENTO.INCLUIR_COMPROVANTES, LINK_COMPARTILHAMENTO.EXPIRA_EM, criacaoAplicada, revogado)
                .from(LINK_COMPARTILHAMENTO)
                .join(criacao).on(criacao.ID.eq(LINK_COMPARTILHAMENTO.OPERACAO_ID))
                .leftJoin(revogacao).on(revogacao.ID.eq(LINK_COMPARTILHAMENTO.REVOGADO_POR))
                .where(condicao)
                .fetchOptional(r -> new Link(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), Boolean.TRUE.equals(r.value6()),
                        Boolean.TRUE.equals(r.value7())));
    }
}
