package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.RETRATO;

import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioRetratos;

/** Persistência dos retratos: só inserções, porque um retrato não muda. */
@Repository
class RepositorioRetratosJooq implements RepositorioRetratos {

    private final DSLContext banco;

    RepositorioRetratosJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Retrato r) {
        banco.insertInto(RETRATO).set(RETRATO.ID, r.id()).set(RETRATO.DONO_ID, operacao.dono())
                .set(RETRATO.CAPACIDADE, r.capacidade()).set(RETRATO.PARAMETROS, JSONB.valueOf(r.parametros()))
                .set(RETRATO.DATA_REFERENCIA, r.dataReferencia()).set(RETRATO.RESULTADO, JSONB.valueOf(r.resultado()))
                .set(RETRATO.CLIENTE, r.cliente())
                .execute();
    }

    @Override
    public Optional<Retrato> buscar(UUID dono, UUID id) {
        return banco.selectFrom(RETRATO).where(RETRATO.ID.eq(id), RETRATO.DONO_ID.eq(dono)).fetchOptional()
                .map(r -> new Retrato(r.getId(), r.getCapacidade(), r.getParametros().data(), r.getDataReferencia(),
                        r.getResultado().data(), r.getCliente()));
    }
}
