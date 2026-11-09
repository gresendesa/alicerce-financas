package tools.alicerce.financeiro.app;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * A invariante "toda escrita tem operação" conferida no banco: lista as linhas de domínio de um
 * dono sem um {@code efeito_operacao} que as tenha criado. Os testes das capacidades de escrita a
 * chamam depois de cada cenário; vazia é o esperado.
 */
final class EscritasRegistradas {

    private EscritasRegistradas() {}

    private static final String SEM_EFEITO = """
            select entidade || ' ' || id from (
              select 'lancamento' as entidade, id from lancamento where dono_id = ?
              union all select 'conta', id from conta where dono_id = ?
              union all select 'cartao_credito', c.conta_id from cartao_credito c join conta x on x.id = c.conta_id where x.dono_id = ?
              union all select 'fatura', id from fatura where dono_id = ?
              union all select 'ambito', id from ambito where dono_id = ?
              union all select 'meio_pagamento', id from meio_pagamento where dono_id = ?
              union all select 'instituicao', id from instituicao where dono_id = ?
              union all select 'comprovante', id from comprovante where dono_id = ?
              union all select 'retrato', id from retrato where dono_id = ?
              union all select 'compromisso', id from compromisso where dono_id = ?
              union all select 'meta', id from meta where dono_id = ?
              union all select 'conferencia', id from conferencia where dono_id = ?
              union all select 'declaracao', id from declaracao where dono_id = ?
              union all select 'link_compartilhamento', id from link_compartilhamento where dono_id = ?
              union all select 'ocorrencia_compromisso', o.id from ocorrencia_compromisso o join compromisso c on c.id = o.compromisso_id
                where c.dono_id = ?
            ) linhas
            where not exists (
              select 1 from efeito_operacao e
              where e.entidade = linhas.entidade and e.entidade_id = linhas.id
                and e.tipo in ('lancamento_criado', 'lancamento_estornado', 'entidade_criada', 'comprovante_vinculado', 'retrato_gravado', 'fatura_criada',
                                 'conferencia_registrada', 'ocorrencia_registrada', 'declaracao_registrada', 'link_criado'))
            order by 1
            """;

    static List<String> semEfeito(DSLContext banco, UUID dono) {
        return banco.fetch(SEM_EFEITO, dono, dono, dono, dono, dono, dono, dono, dono, dono, dono, dono, dono, dono, dono, dono).getValues(0, String.class);
    }
}
