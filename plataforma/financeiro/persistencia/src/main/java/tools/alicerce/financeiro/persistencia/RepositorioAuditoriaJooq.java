package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.EVENTO_AUDITORIA;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.RepositorioAuditoria;

@Repository
class RepositorioAuditoriaJooq implements RepositorioAuditoria {

    private final DSLContext banco;

    RepositorioAuditoriaJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void registrar(EventoAuditoria evento) {
        banco.insertInto(EVENTO_AUDITORIA)
                .set(EVENTO_AUDITORIA.DONO_ID, evento.dono())
                .set(EVENTO_AUDITORIA.CLIENTE, evento.cliente())
                .set(EVENTO_AUDITORIA.ESCOPOS, evento.escopos().stream().sorted().toArray(String[]::new))
                .set(EVENTO_AUDITORIA.CAPACIDADE, evento.capacidade())
                .set(EVENTO_AUDITORIA.CHAVE_IDEMPOTENCIA, evento.chaveIdempotencia())
                .set(EVENTO_AUDITORIA.ARGUMENTOS, JSONB.valueOf(evento.argumentosJson()))
                .set(EVENTO_AUDITORIA.RESULTADO, evento.codigoErro() == null ? "sucesso" : "erro")
                .set(EVENTO_AUDITORIA.CODIGO_ERRO, evento.codigoErro() == null ? null : evento.codigoErro().codigo())
                .set(EVENTO_AUDITORIA.OPERACAO_ID, evento.operacao())
                .execute();
    }
}
