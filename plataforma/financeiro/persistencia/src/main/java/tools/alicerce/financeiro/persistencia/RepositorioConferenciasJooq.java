package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONFERENCIA;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioConferencias;

/** Persistência das conferências: só INSERT; a de operação desfeita deixa de valer. */
@Repository
class RepositorioConferenciasJooq implements RepositorioConferencias {

    private static final String QUE_VALEM = """
            select f.id, f.conta_id, f.fatura_id, f.data, f.informado_centavos, f.calculado_centavos, f.moeda, f.ajuste_lancamento_id,
                   (f.ajuste_lancamento_id is not null and not lancamento_anulado(f.ajuste_lancamento_id)) as ajuste_vigente,
                   r.codigo as operacao, f.registrada_em
              from conferencia f
              join operacao op on op.id = f.operacao_id
              left join referencia_curta r on r.entidade_id = op.id and r.tipo_entidade = 'operacao'
             where f.dono_id = ? and op.estado = 'aplicada'
             order by f.data desc, f.ordem desc
            """;

    private final DSLContext banco;

    RepositorioConferenciasJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Conferencia c) {
        banco.insertInto(CONFERENCIA).set(CONFERENCIA.ID, c.id()).set(CONFERENCIA.DONO_ID, operacao.dono()).set(CONFERENCIA.CONTA_ID, c.conta())
                .set(CONFERENCIA.FATURA_ID, c.fatura()).set(CONFERENCIA.DATA, c.data()).set(CONFERENCIA.INFORMADO_CENTAVOS, c.informadoCentavos())
                .set(CONFERENCIA.CALCULADO_CENTAVOS, c.calculadoCentavos()).set(CONFERENCIA.MOEDA, c.moeda())
                .set(CONFERENCIA.AJUSTE_LANCAMENTO_ID, c.ajuste()).set(CONFERENCIA.OPERACAO_ID, operacao.id()).execute();
    }

    @Override
    public List<Conferencia> conferencias(UUID dono) {
        return banco.fetch(QUE_VALEM, dono).map(r -> new Conferencia(r.get("id", UUID.class), r.get("conta_id", UUID.class),
                r.get("fatura_id", UUID.class), r.get("data", LocalDate.class), r.get("informado_centavos", Long.class),
                r.get("calculado_centavos", Long.class), r.get("moeda", String.class), r.get("ajuste_lancamento_id", UUID.class),
                Boolean.TRUE.equals(r.get("ajuste_vigente", Boolean.class)), r.get("operacao", String.class),
                r.get("registrada_em", OffsetDateTime.class)));
    }
}
