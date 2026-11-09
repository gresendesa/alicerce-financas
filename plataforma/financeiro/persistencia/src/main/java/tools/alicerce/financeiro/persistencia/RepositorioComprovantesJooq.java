package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.COMPROVANTE;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EFEITO_OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.TOKEN_ANEXO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioComprovantes;
import tools.alicerce.financeiro.persistencia.jooq.tables.records.ComprovanteRecord;

/** Índice dos comprovantes e tokens de envio. */
@Repository
class RepositorioComprovantesJooq implements RepositorioComprovantes {

    private final DSLContext banco;

    RepositorioComprovantesJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Indice i) {
        banco.insertInto(COMPROVANTE).set(COMPROVANTE.ID, i.id()).set(COMPROVANTE.DONO_ID, operacao.dono())
                .set(COMPROVANTE.LANCAMENTO_ID, i.lancamento()).set(COMPROVANTE.TIPO_MIDIA, i.tipoMidia())
                .set(COMPROVANTE.TAMANHO_BYTES, i.tamanhoBytes()).set(COMPROVANTE.CHAVE_ARMAZENAMENTO, i.chave())
                .set(COMPROVANTE.SHA256, i.sha256()).set(COMPROVANTE.NATUREZA, i.natureza()).execute();
    }

    @Override
    public void vincular(OperacaoEmCurso operacao, UUID comprovante, UUID lancamento) {
        banco.update(COMPROVANTE).set(COMPROVANTE.LANCAMENTO_ID, lancamento)
                .where(COMPROVANTE.ID.eq(comprovante), COMPROVANTE.DONO_ID.eq(operacao.dono())).execute();
    }

    @Override
    public Optional<Indice> buscar(UUID dono, UUID id) {
        return banco.selectFrom(COMPROVANTE).where(COMPROVANTE.ID.eq(id), COMPROVANTE.DONO_ID.eq(dono))
                .fetchOptional(RepositorioComprovantesJooq::indice);
    }

    @Override
    public List<Indice> doLancamento(UUID dono, UUID lancamento) {
        return banco.selectFrom(COMPROVANTE).where(COMPROVANTE.LANCAMENTO_ID.eq(lancamento), COMPROVANTE.DONO_ID.eq(dono))
                .orderBy(COMPROVANTE.ENVIADO_EM, COMPROVANTE.ID).fetch(RepositorioComprovantesJooq::indice);
    }

    @Override
    public void inserirToken(OperacaoEmCurso operacao, UUID id, String hash, UUID lancamento, OffsetDateTime expiraEm) {
        banco.insertInto(TOKEN_ANEXO).set(TOKEN_ANEXO.ID, id).set(TOKEN_ANEXO.HASH_TOKEN, hash)
                .set(TOKEN_ANEXO.DONO_ID, operacao.dono()).set(TOKEN_ANEXO.LANCAMENTO_ID, lancamento)
                .set(TOKEN_ANEXO.EXPIRA_EM, expiraEm).execute();
    }

    @Override
    public Optional<TokenAnexo> token(String hash) {
        // O cliente vem da operação que emitiu o link: o envio pela interface aparece na trilha como dela.
        return banco.select(TOKEN_ANEXO.DONO_ID, TOKEN_ANEXO.LANCAMENTO_ID, TOKEN_ANEXO.EXPIRA_EM, TOKEN_ANEXO.USADO_EM, OPERACAO.CLIENTE)
                .from(TOKEN_ANEXO)
                .leftJoin(EFEITO_OPERACAO).on(EFEITO_OPERACAO.ENTIDADE_ID.eq(TOKEN_ANEXO.ID), EFEITO_OPERACAO.TIPO.eq("token_anexo_emitido"))
                .leftJoin(OPERACAO).on(OPERACAO.ID.eq(EFEITO_OPERACAO.OPERACAO_ID))
                .where(TOKEN_ANEXO.HASH_TOKEN.eq(hash))
                .fetchOptional(r -> new TokenAnexo(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }

    @Override
    public boolean consumirToken(OperacaoEmCurso operacao, String hash, OffsetDateTime agora) {
        return banco.update(TOKEN_ANEXO).set(TOKEN_ANEXO.USADO_EM, agora)
                .where(TOKEN_ANEXO.HASH_TOKEN.eq(hash), TOKEN_ANEXO.DONO_ID.eq(operacao.dono()), TOKEN_ANEXO.USADO_EM.isNull(),
                        TOKEN_ANEXO.EXPIRA_EM.gt(agora))
                .execute() == 1;
    }

    private static Indice indice(ComprovanteRecord r) {
        return new Indice(r.getId(), r.getLancamentoId(), r.getTipoMidia(), r.getTamanhoBytes(), r.getChaveArmazenamento(),
                r.getSha256(), r.getEnviadoEm(), r.getNatureza());
    }
}
