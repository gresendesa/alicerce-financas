package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.RepositorioDono;
import tools.alicerce.financeiro.dominio.Dono;

@Repository
class RepositorioDonoJooq implements RepositorioDono {

    private final DSLContext banco;

    RepositorioDonoJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public UUID garantir(String emissor, String sujeito) {
        // Dois primeiros acessos simultâneos: um insere, o outro não faz nada; ambos leem o mesmo id.
        banco.insertInto(DONO)
                .set(DONO.ID, UUID.randomUUID())
                .set(DONO.EMISSOR_OIDC, emissor)
                .set(DONO.SUJEITO_OIDC, sujeito)
                .onConflict(DONO.EMISSOR_OIDC, DONO.SUJEITO_OIDC).doNothing()
                .execute();
        return banco.select(DONO.ID).from(DONO)
                .where(DONO.EMISSOR_OIDC.eq(emissor), DONO.SUJEITO_OIDC.eq(sujeito))
                .fetchSingle(DONO.ID);
    }

    @Override
    public Optional<Dono> buscar(UUID id) {
        return banco.selectFrom(DONO).where(DONO.ID.eq(id)).fetchOptional()
                .map(linha -> new Dono(linha.getId(), ZoneId.of(linha.getFuso()), Currency.getInstance(linha.getMoeda())));
    }
}
