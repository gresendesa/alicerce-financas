package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.META;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioMetas;
import tools.alicerce.financeiro.dominio.Meta;
import tools.alicerce.financeiro.dominio.SituacaoMeta;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Persistência das metas (estrutura mutável, com versões) e a leitura dos aportes marcados no livro-razão. */
@Repository
class RepositorioMetasJooq implements RepositorioMetas {

    /**
     * Os aportes e resgates vigentes: lançamento aporte_meta não anulado. O valor é a soma das entradas do lançamento na
     * moeda da meta, o mesmo que a transferência moveu.
     */
    private static final String VIGENTES = """
            select l.id, l.meta_id, l.meta_resgate, l.data, l.descricao, l.operacao_id, r.codigo,
                   (select sum(mv.valor_centavos) from movimento mv
                     where mv.lancamento_id = l.id and mv.valor_centavos > 0 and mv.moeda = m.moeda)::bigint as centavos
              from lancamento l
              join meta m on m.id = l.meta_id
              left join referencia_curta r on r.tipo_entidade = 'lancamento' and r.entidade_id = l.id
             where m.dono_id = ? and l.tipo = 'aporte_meta' and not lancamento_anulado(l.id)
               %s
             order by l.data, l.registrado_em, l.id
            """;

    private final DSLContext banco;

    RepositorioMetasJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public List<Meta> metas(UUID dono) {
        return banco.selectFrom(META).where(META.DONO_ID.eq(dono)).orderBy(META.NOME).fetch(RepositorioMetasJooq::meta);
    }

    private static Meta meta(Record r) {
        return new Meta(r.get(META.ID), r.get(META.NOME), List.of(r.get(META.ALIASES)), r.get(META.ALVO_CENTAVOS), r.get(META.MOEDA),
                r.get(META.DATA_ALVO), r.get(META.PRIORIDADE), r.get(META.CONTA_RESERVADA_ID),
                SituacaoMeta.deCodigo(r.get(META.SITUACAO)).orElseThrow(), r.get(META.OBSERVACAO), r.get(META.ARQUIVADA), r.get(META.VERSAO));
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Meta m) {
        banco.insertInto(META).set(META.ID, m.id()).set(META.DONO_ID, operacao.dono()).set(campos(m)).execute();
    }

    @Override
    public void atualizar(OperacaoEmCurso operacao, Meta m) {
        banco.update(META).set(campos(m)).where(META.ID.eq(m.id()), META.DONO_ID.eq(operacao.dono())).execute();
    }

    private static Map<org.jooq.Field<?>, Object> campos(Meta m) {
        Map<org.jooq.Field<?>, Object> campos = new HashMap<>();
        campos.put(META.NOME, m.nome());
        campos.put(META.ALIASES, m.aliases().toArray(String[]::new));
        campos.put(META.ALVO_CENTAVOS, m.alvoCentavos());
        campos.put(META.MOEDA, m.moeda());
        campos.put(META.DATA_ALVO, m.dataAlvo());
        campos.put(META.PRIORIDADE, (short) m.prioridade());
        campos.put(META.CONTA_RESERVADA_ID, m.contaReservada());
        campos.put(META.SITUACAO, m.situacao().codigo());
        campos.put(META.OBSERVACAO, m.observacao());
        campos.put(META.ARQUIVADA, m.arquivada());
        campos.put(META.VERSAO, m.versao());
        return campos;
    }

    private record Vigente(UUID meta, UUID operacao, Aporte aporte) {}

    private List<Vigente> vigentes(UUID dono, String filtro, Object... extras) {
        java.util.List<Object> parametros = new java.util.ArrayList<>(List.of(dono));
        parametros.addAll(List.of(extras));
        return banco.fetch(VIGENTES.formatted(filtro), parametros.toArray()).map(r -> {
            Long centavos = r.get("centavos", Long.class);
            return new Vigente(r.get("meta_id", UUID.class), r.get("operacao_id", UUID.class), new Aporte(r.get("id", UUID.class),
                    r.get("codigo", String.class), r.get("data", LocalDate.class), r.get("descricao", String.class),
                    centavos == null ? 0 : centavos, Boolean.TRUE.equals(r.get("meta_resgate", Boolean.class))));
        });
    }

    @Override
    public Map<UUID, Long> progressos(UUID dono) {
        Map<UUID, Long> progressos = new HashMap<>();
        for (Vigente v : vigentes(dono, "")) {
            long valor = v.aporte().resgate() ? -v.aporte().centavos() : v.aporte().centavos();
            progressos.merge(v.meta(), valor, Math::addExact);
        }
        return progressos;
    }

    @Override
    public List<Aporte> aportes(UUID meta) {
        UUID dono = donoDa(meta);
        return dono == null ? List.of() : vigentes(dono, "and m.id = ?", meta).stream().map(Vigente::aporte).toList();
    }

    @Override
    public Set<UUID> operacoesComAportes(UUID meta) {
        UUID dono = donoDa(meta);
        Set<UUID> operacoes = new LinkedHashSet<>();
        if (dono != null) {
            vigentes(dono, "and m.id = ?", meta).forEach(v -> operacoes.add(v.operacao()));
        }
        return operacoes;
    }

    private UUID donoDa(UUID meta) {
        return banco.select(META.DONO_ID).from(META).where(META.ID.eq(meta)).fetchOne(META.DONO_ID);
    }

    @Override
    public Map<UUID, String> referencias(UUID dono) {
        return banco.select(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.DONO_ID.eq(dono), REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.META.entidade()))
                .fetchMap(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO);
    }
}
