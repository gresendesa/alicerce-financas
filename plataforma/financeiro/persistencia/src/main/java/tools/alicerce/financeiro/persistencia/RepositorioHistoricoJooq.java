package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.AMBITO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CARTAO_CREDITO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.COMPROMISSO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.META_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EFEITO_OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EVENTO_AUDITORIA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.INSTITUICAO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MEIO_PAGAMENTO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.RESULTADO_OPERACAO;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SelectOnConditionStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.EntidadeVersionada;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico;
import tools.alicerce.financeiro.aplicacao.TipoEfeito;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Leitura do histórico de escritas. */
@Repository
class RepositorioHistoricoJooq implements RepositorioHistorico {

    private record Versoes(Table<?> tabela, Field<UUID> id, Field<Long> versao, Field<JSONB> dados, Field<UUID> operacao) {}

    private final DSLContext banco;

    RepositorioHistoricoJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public Optional<Operacao> buscar(UUID dono, UUID id) {
        return consulta().where(OPERACAO.DONO_ID.eq(dono), OPERACAO.ID.eq(id)).fetchOptional(RepositorioHistoricoJooq::operacao);
    }

    @Override
    public Optional<Operacao> travar(UUID dono, UUID id) {
        return consulta().where(OPERACAO.DONO_ID.eq(dono), OPERACAO.ID.eq(id)).forUpdate().of(OPERACAO)
                .fetchOptional(RepositorioHistoricoJooq::operacao);
    }

    @Override
    public List<Operacao> travarLote(UUID dono, String lote) {
        return consulta().where(OPERACAO.DONO_ID.eq(dono), OPERACAO.LOTE.eq(lote))
                .orderBy(OPERACAO.CRIADA_EM, OPERACAO.ID).forUpdate().of(OPERACAO)
                .fetch(RepositorioHistoricoJooq::operacao);
    }

    @Override
    public List<Efeito> efeitos(UUID operacao) {
        return banco.selectFrom(EFEITO_OPERACAO).where(EFEITO_OPERACAO.OPERACAO_ID.eq(operacao))
                .orderBy(EFEITO_OPERACAO.ORDEM)
                .fetch(r -> new Efeito(r.getOrdem(), TipoEfeito.deCodigo(r.getTipo()), r.getEntidade(), r.getEntidadeId(),
                        r.getVersaoAnterior(), r.getDetalhe()));
    }

    @Override
    public Optional<Versao> versao(EntidadeVersionada entidade, UUID id, long versao) {
        Versoes v = versoes(entidade);
        return banco.select(v.versao(), v.dados(), v.operacao()).from(v.tabela())
                .where(v.id().eq(id), v.versao().eq(versao))
                .fetchOptional(r -> new Versao(r.value1(), r.value2().data(), r.value3()));
    }

    @Override
    public Optional<Versao> ultimaVersao(EntidadeVersionada entidade, UUID id) {
        Versoes v = versoes(entidade);
        return banco.select(v.versao(), v.dados(), v.operacao()).from(v.tabela())
                .where(v.id().eq(id)).orderBy(v.versao().desc()).limit(1)
                .fetchOptional(r -> new Versao(r.value1(), r.value2().data(), r.value3()));
    }

    @Override
    public List<String> chavesQueSairamDe(UUID lancamento) {
        return banco.selectDistinct(EFEITO_OPERACAO.DETALHE).from(EFEITO_OPERACAO)
                .where(EFEITO_OPERACAO.TIPO.eq(TipoEfeito.CHAVE_FISCAL_MOVIDA.codigo()),
                        EFEITO_OPERACAO.ENTIDADE_ID.eq(lancamento))
                .fetch(EFEITO_OPERACAO.DETALHE);
    }

    @Override
    public Optional<UUID> ultimoVinculo(UUID comprovante) {
        return banco.select(EFEITO_OPERACAO.OPERACAO_ID).from(EFEITO_OPERACAO).join(OPERACAO)
                .on(OPERACAO.ID.eq(EFEITO_OPERACAO.OPERACAO_ID))
                .where(EFEITO_OPERACAO.TIPO.eq(TipoEfeito.COMPROVANTE_VINCULADO.codigo()),
                        EFEITO_OPERACAO.ENTIDADE_ID.eq(comprovante))
                .orderBy(OPERACAO.CRIADA_EM.desc()).limit(1).fetchOptional(EFEITO_OPERACAO.OPERACAO_ID);
    }

    @Override
    public List<Operacao> listar(UUID dono, Filtro f, int limite) {
        List<Condition> condicoes = new ArrayList<>();
        condicoes.add(OPERACAO.DONO_ID.eq(dono));
        if (!f.incluirDesfeitas()) {
            condicoes.add(OPERACAO.ESTADO.eq("aplicada"));
        }
        if (f.lote() != null) {
            condicoes.add(OPERACAO.LOTE.eq(f.lote()));
        }
        if (f.cliente() != null) {
            condicoes.add(OPERACAO.CLIENTE.eq(f.cliente()));
        }
        if (f.capacidade() != null) {
            condicoes.add(OPERACAO.CAPACIDADE.eq(f.capacidade()));
        }
        if (!f.semCapacidades().isEmpty()) {
            condicoes.add(OPERACAO.CAPACIDADE.notIn(f.semCapacidades()));
        }
        if (f.de() != null) {
            condicoes.add(OPERACAO.CRIADA_EM.ge(f.de()));
        }
        if (f.ate() != null) {
            condicoes.add(OPERACAO.CRIADA_EM.lt(f.ate()));
        }
        if (f.depoisDe() != null) {
            condicoes.add(DSL.row(OPERACAO.CRIADA_EM, OPERACAO.ID).lessThan(f.depoisDe().criadaEm(), f.depoisDe().id()));
        }
        return consulta().where(condicoes).orderBy(OPERACAO.CRIADA_EM.desc(), OPERACAO.ID.desc()).limit(limite)
                .fetch(RepositorioHistoricoJooq::operacao);
    }

    @Override
    public List<Recusa> recusas(UUID dono, FiltroRecusas f, int limite) {
        List<Condition> condicoes = new ArrayList<>();
        condicoes.add(EVENTO_AUDITORIA.DONO_ID.eq(dono));
        condicoes.add(EVENTO_AUDITORIA.RESULTADO.eq("erro"));
        if (f.cliente() != null) {
            condicoes.add(EVENTO_AUDITORIA.CLIENTE.eq(f.cliente()));
        }
        if (!f.semCapacidades().isEmpty()) {
            condicoes.add(EVENTO_AUDITORIA.CAPACIDADE.notIn(f.semCapacidades()));
        }
        if (f.de() != null) {
            condicoes.add(EVENTO_AUDITORIA.OCORRIDO_EM.ge(f.de()));
        }
        if (f.ate() != null) {
            condicoes.add(EVENTO_AUDITORIA.OCORRIDO_EM.lt(f.ate()));
        }
        if (f.depoisDe() != null) {
            condicoes.add(DSL.row(EVENTO_AUDITORIA.OCORRIDO_EM, EVENTO_AUDITORIA.ID).lessThan(f.depoisDe().ocorridaEm(), f.depoisDe().id()));
        }
        return banco.select(EVENTO_AUDITORIA.ID, EVENTO_AUDITORIA.CAPACIDADE, EVENTO_AUDITORIA.CLIENTE, EVENTO_AUDITORIA.CODIGO_ERRO,
                        EVENTO_AUDITORIA.OCORRIDO_EM)
                .from(EVENTO_AUDITORIA).where(condicoes)
                .orderBy(EVENTO_AUDITORIA.OCORRIDO_EM.desc(), EVENTO_AUDITORIA.ID.desc()).limit(limite)
                .fetch(r -> new Recusa(r.value1(), r.value2(), r.value3(), r.value4(), r.value5()));
    }

    @Override
    public List<String> clientes(UUID dono) {
        return banco.select(OPERACAO.CLIENTE).from(OPERACAO).where(OPERACAO.DONO_ID.eq(dono))
                .union(DSL.select(EVENTO_AUDITORIA.CLIENTE).from(EVENTO_AUDITORIA)
                        .where(EVENTO_AUDITORIA.DONO_ID.eq(dono), EVENTO_AUDITORIA.RESULTADO.eq("erro")))
                .orderBy(DSL.field(DSL.name("cliente")))
                .fetch(0, String.class);
    }

    @Override
    public List<EfeitoReferenciado> efeitosReferenciados(UUID operacao) {
        // O estorno é uma linha nova sem endereço: o registro que o dono reconhece é o lançamento estornado.
        Field<UUID> alvo = DSL.coalesce(LANCAMENTO.ESTORNA_LANCAMENTO_ID, EFEITO_OPERACAO.ENTIDADE_ID);
        Field<String> codigo = DSL.field(DSL.select(REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.ENTIDADE_ID.eq(alvo)).limit(1));
        return banco.select(EFEITO_OPERACAO.TIPO, EFEITO_OPERACAO.ENTIDADE, codigo)
                .from(EFEITO_OPERACAO)
                .leftJoin(LANCAMENTO).on(EFEITO_OPERACAO.TIPO.eq(TipoEfeito.LANCAMENTO_ESTORNADO.codigo()),
                        LANCAMENTO.ID.eq(EFEITO_OPERACAO.ENTIDADE_ID))
                .where(EFEITO_OPERACAO.OPERACAO_ID.eq(operacao))
                .orderBy(EFEITO_OPERACAO.ORDEM)
                .fetch(r -> new EfeitoReferenciado(TipoEfeito.deCodigo(r.value1()), r.value2(), r.value3()));
    }

    @Override
    public List<String> operacoesDoRegistro(UUID dono, String referencia) {
        var registro = DSL.select(REFERENCIA_CURTA.ENTIDADE_ID).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.CODIGO.eq(referencia), REFERENCIA_CURTA.DONO_ID.eq(dono));
        var estornos = DSL.select(LANCAMENTO.ID).from(LANCAMENTO)
                .where(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.ESTORNA_LANCAMENTO_ID.in(registro));
        var codigoOperacao = REFERENCIA_CURTA.as("codigo_operacao");
        List<String> mudancas = List.of(TipoEfeito.LANCAMENTO_CRIADO.codigo(), TipoEfeito.LANCAMENTO_ESTORNADO.codigo(),
                TipoEfeito.ENTIDADE_CRIADA.codigo(), TipoEfeito.ENTIDADE_ALTERADA.codigo());
        return banco.select(codigoOperacao.CODIGO, OPERACAO.CRIADA_EM, OPERACAO.ID)
                .from(OPERACAO)
                .join(codigoOperacao).on(codigoOperacao.ENTIDADE_ID.eq(OPERACAO.ID),
                        codigoOperacao.TIPO_ENTIDADE.eq(TipoReferencia.OPERACAO.entidade()))
                .where(OPERACAO.DONO_ID.eq(dono), DSL.exists(DSL.selectOne().from(EFEITO_OPERACAO)
                        .where(EFEITO_OPERACAO.OPERACAO_ID.eq(OPERACAO.ID), EFEITO_OPERACAO.TIPO.in(mudancas),
                                EFEITO_OPERACAO.ENTIDADE_ID.in(registro).or(EFEITO_OPERACAO.ENTIDADE_ID.in(estornos)))))
                .orderBy(OPERACAO.CRIADA_EM, OPERACAO.ID)
                .fetch(codigoOperacao.CODIGO);
    }

    private SelectOnConditionStep<? extends Record> consulta() {
        return banco.select(OPERACAO.ID, REFERENCIA_CURTA.CODIGO, OPERACAO.CAPACIDADE, OPERACAO.ARGUMENTOS,
                        RESULTADO_OPERACAO.RESULTADO, OPERACAO.LOTE, OPERACAO.CLIENTE, OPERACAO.ESTADO, OPERACAO.DESFEITA_POR,
                        OPERACAO.CRIADA_EM)
                .from(OPERACAO)
                .join(REFERENCIA_CURTA).on(REFERENCIA_CURTA.ENTIDADE_ID.eq(OPERACAO.ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.OPERACAO.entidade()))
                .leftJoin(RESULTADO_OPERACAO).on(RESULTADO_OPERACAO.OPERACAO_ID.eq(OPERACAO.ID));
    }

    private static Operacao operacao(Record r) {
        JSONB resultado = r.get(RESULTADO_OPERACAO.RESULTADO);
        return new Operacao(r.get(OPERACAO.ID), r.get(REFERENCIA_CURTA.CODIGO), r.get(OPERACAO.CAPACIDADE),
                r.get(OPERACAO.ARGUMENTOS).data(), resultado == null ? null : resultado.data(), r.get(OPERACAO.LOTE),
                r.get(OPERACAO.CLIENTE), "desfeita".equals(r.get(OPERACAO.ESTADO)), r.get(OPERACAO.DESFEITA_POR),
                r.get(OPERACAO.CRIADA_EM));
    }

    private static Versoes versoes(EntidadeVersionada entidade) {
        return switch (entidade) {
            case INSTITUICAO -> new Versoes(INSTITUICAO_VERSAO, INSTITUICAO_VERSAO.INSTITUICAO_ID, INSTITUICAO_VERSAO.VERSAO,
                    INSTITUICAO_VERSAO.DADOS, INSTITUICAO_VERSAO.OPERACAO_ID);
            case CONTA -> new Versoes(CONTA_VERSAO, CONTA_VERSAO.CONTA_ID, CONTA_VERSAO.VERSAO, CONTA_VERSAO.DADOS,
                    CONTA_VERSAO.OPERACAO_ID);
            case CARTAO_CREDITO -> new Versoes(CARTAO_CREDITO_VERSAO, CARTAO_CREDITO_VERSAO.CARTAO_CREDITO_ID, CARTAO_CREDITO_VERSAO.VERSAO,
                    CARTAO_CREDITO_VERSAO.DADOS, CARTAO_CREDITO_VERSAO.OPERACAO_ID);
            case AMBITO -> new Versoes(AMBITO_VERSAO, AMBITO_VERSAO.AMBITO_ID, AMBITO_VERSAO.VERSAO, AMBITO_VERSAO.DADOS,
                    AMBITO_VERSAO.OPERACAO_ID);
            case MEIO_PAGAMENTO -> new Versoes(MEIO_PAGAMENTO_VERSAO, MEIO_PAGAMENTO_VERSAO.MEIO_PAGAMENTO_ID,
                    MEIO_PAGAMENTO_VERSAO.VERSAO, MEIO_PAGAMENTO_VERSAO.DADOS, MEIO_PAGAMENTO_VERSAO.OPERACAO_ID);
            case COMPROMISSO -> new Versoes(COMPROMISSO_VERSAO, COMPROMISSO_VERSAO.COMPROMISSO_ID, COMPROMISSO_VERSAO.VERSAO,
                    COMPROMISSO_VERSAO.DADOS, COMPROMISSO_VERSAO.OPERACAO_ID);
            case META -> new Versoes(META_VERSAO, META_VERSAO.META_ID, META_VERSAO.VERSAO, META_VERSAO.DADOS, META_VERSAO.OPERACAO_ID);
        };
    }
}
