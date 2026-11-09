package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.DECLARACAO;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioDeclaracoes;
import tools.alicerce.financeiro.dominio.TipoDeclaracao;

/** Persistência das declarações do perfil: só INSERT; a de operação desfeita deixa de valer. */
@Repository
class RepositorioDeclaracoesJooq implements RepositorioDeclaracoes {

    private static final String QUE_VALEM = """
            select d.id, d.tipo, d.valor::text as valor, d.vigente_desde, d.observacao, r.codigo as operacao, d.registrada_em
              from declaracao d
              join operacao op on op.id = d.operacao_id
              left join referencia_curta r on r.entidade_id = op.id and r.tipo_entidade = 'operacao'
             where d.dono_id = ? and op.estado = 'aplicada'
             order by d.ordem desc
            """;

    private final DSLContext banco;

    RepositorioDeclaracoesJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Declaracao d) {
        banco.insertInto(DECLARACAO).set(DECLARACAO.ID, d.id()).set(DECLARACAO.DONO_ID, operacao.dono()).set(DECLARACAO.TIPO, d.tipo().codigo())
                .set(DECLARACAO.VALOR, JSONB.valueOf(d.valor())).set(DECLARACAO.VIGENTE_DESDE, d.vigenteDesde())
                .set(DECLARACAO.OBSERVACAO, d.observacao()).set(DECLARACAO.OPERACAO_ID, operacao.id()).execute();
    }

    @Override
    public List<Declaracao> declaracoes(UUID dono) {
        return banco.fetch(QUE_VALEM, dono).map(r -> new Declaracao(r.get("id", UUID.class),
                TipoDeclaracao.deCodigo(r.get("tipo", String.class)).orElseThrow(), r.get("valor", String.class),
                r.get("vigente_desde", LocalDate.class), r.get("observacao", String.class), r.get("operacao", String.class),
                r.get("registrada_em", OffsetDateTime.class)));
    }
}
