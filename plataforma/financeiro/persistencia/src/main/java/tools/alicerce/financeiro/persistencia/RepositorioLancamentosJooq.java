package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.CHAVE_FISCAL;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.ITEM_LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MOVIMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioLancamentos;
import tools.alicerce.financeiro.dominio.CalculoConversao;
import tools.alicerce.financeiro.dominio.FatoPeriodo;
import tools.alicerce.financeiro.dominio.ItemLancamento;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.dominio.ValorOriginal;
import tools.alicerce.financeiro.persistencia.jooq.Routines;
import tools.alicerce.financeiro.persistencia.jooq.enums.ClasseConta;
import tools.alicerce.financeiro.persistencia.jooq.enums.OrigemLancamento;
import tools.alicerce.financeiro.persistencia.jooq.enums.ProcedenciaLancamento;

/** Persistência dos lançamentos: só inserções, porque o livro-razão é imutável. */
@Repository
class RepositorioLancamentosJooq implements RepositorioLancamentos {

    private final DSLContext banco;

    RepositorioLancamentosJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, Lancamento l) {
        ValorOriginal o = l.original();
        banco.insertInto(LANCAMENTO).set(LANCAMENTO.ID, l.id()).set(LANCAMENTO.DONO_ID, operacao.dono())
                .set(LANCAMENTO.OPERACAO_ID, operacao.id()).set(LANCAMENTO.TIPO, tipo(l.tipo())).set(LANCAMENTO.DATA, l.data())
                .set(LANCAMENTO.DESCRICAO, l.descricao()).set(LANCAMENTO.CONTRAPARTE, l.contraparte())
                .set(LANCAMENTO.OBSERVACAO, l.observacao()).set(LANCAMENTO.MEIO_PAGAMENTO_ID, l.meioPagamento())
                .set(LANCAMENTO.ORIGEM, OrigemLancamento.valueOf(l.origem().codigo()))
                .set(LANCAMENTO.CNPJ_ESTABELECIMENTO, l.cnpjEstabelecimento())
                .set(LANCAMENTO.ESTORNA_LANCAMENTO_ID, l.estorna())
                .set(LANCAMENTO.VALOR_ORIGINAL_MINIMO, o == null ? null : Long.valueOf(o.unidadesMinimas()))
                .set(LANCAMENTO.MOEDA_ORIGINAL, o == null ? null : o.moeda().getCurrencyCode())
                .set(LANCAMENTO.COTACAO, o == null ? null : o.cotacao())
                .set(LANCAMENTO.DATA_COTACAO, o == null ? null : o.dataCotacao())
                .set(LANCAMENTO.PRECO_A_VISTA_CENTAVOS, l.precoAVista() == null ? null : Long.valueOf(l.precoAVista().centavos()))
                .set(LANCAMENTO.PROCEDENCIA, ProcedenciaLancamento.valueOf(l.procedencia().codigo()))
                .set(LANCAMENTO.CONTA_HISTORICA_ID, l.contaHistorica())
                .set(LANCAMENTO.META_ID, l.aporte() == null ? null : l.aporte().meta())
                .set(LANCAMENTO.META_RESGATE, l.aporte() == null ? null : l.aporte().resgate())
                .execute();
        for (Movimento m : l.movimentos()) {
            banco.insertInto(MOVIMENTO).set(MOVIMENTO.ID, UUID.randomUUID()).set(MOVIMENTO.LANCAMENTO_ID, l.id())
                    .set(MOVIMENTO.CONTA_ID, m.conta()).set(MOVIMENTO.MOEDA, m.valor().moeda().getCurrencyCode())
                    .set(MOVIMENTO.VALOR_CENTAVOS, m.valor().centavos()).set(MOVIMENTO.AMBITO_ID, m.ambito())
                    .set(MOVIMENTO.FATURA_ID, m.fatura())
                    .set(MOVIMENTO.PARCELA, m.parcela() == null ? null : m.parcela().shortValue())
                    .set(MOVIMENTO.TOTAL_PARCELAS, m.totalParcelas() == null ? null : m.totalParcelas().shortValue())
                    .set(MOVIMENTO.FATURA_ABERTURA_ID, m.faturaAbertura())
                    .execute();
        }
        short posicao = 0;
        for (ItemLancamento i : l.itens()) {
            posicao++;
            banco.insertInto(ITEM_LANCAMENTO).set(ITEM_LANCAMENTO.ID, UUID.randomUUID())
                    .set(ITEM_LANCAMENTO.LANCAMENTO_ID, l.id()).set(ITEM_LANCAMENTO.POSICAO, posicao)
                    .set(ITEM_LANCAMENTO.DESCRICAO, i.descricao()).set(ITEM_LANCAMENTO.QUANTIDADE, i.quantidade())
                    .set(ITEM_LANCAMENTO.CONTA_ID, i.categoria()).set(ITEM_LANCAMENTO.MOEDA, i.valor().moeda().getCurrencyCode())
                    .set(ITEM_LANCAMENTO.VALOR_CENTAVOS, i.valor().centavos()).set(ITEM_LANCAMENTO.AMBITO_ID, i.ambito())
                    .execute();
        }
    }

    @Override
    public Optional<Lancamento> buscar(UUID dono, UUID id) {
        return banco.selectFrom(LANCAMENTO).where(LANCAMENTO.ID.eq(id), LANCAMENTO.DONO_ID.eq(dono)).fetchOptional()
                .map(r -> new Lancamento(r.getId(), dominio(r.getTipo()), r.getData(), r.getDescricao(), r.getContraparte(),
                        r.getObservacao(), r.getMeioPagamentoId(),
                        tools.alicerce.financeiro.dominio.OrigemLancamento.deCodigo(r.getOrigem().getLiteral()).orElseThrow(),
                        r.getCnpjEstabelecimento(), movimentos(id), itens(id), r.getEstornaLancamentoId(),
                        original(r.getValorOriginalMinimo(), r.getMoedaOriginal(), r.getCotacao(), r.getDataCotacao()),
                        precoAVista(r.getPrecoAVistaCentavos(), id), procedencia(r.getProcedencia(), dominio(r.getTipo())),
                        r.getContaHistoricaId(), r.getMetaId() == null ? null
                                : new tools.alicerce.financeiro.dominio.AporteMeta(r.getMetaId(), Boolean.TRUE.equals(r.getMetaResgate()))));
    }

    /** Linhas anteriores à coluna ficam nulas: o livro-razão não recebe UPDATE. */
    private static Procedencia procedencia(ProcedenciaLancamento gravada, TipoLancamento tipo) {
        return gravada == null ? Procedencia.legado(tipo) : Procedencia.valueOf(gravada.name().toUpperCase(Locale.ROOT));
    }

    /**
     * Nunca vale NULL: a linha antiga, sem procedência gravada, é endógena, salvo a abertura. Com {@code eq}, o
     * {@code not(...)} desta condição tirava essas linhas da visão caixa.
     */
    private static Condition exogeno() {
        return LANCAMENTO.PROCEDENCIA.isNotDistinctFrom(ProcedenciaLancamento.exogeno)
                .or(LANCAMENTO.PROCEDENCIA.isNull().and(LANCAMENTO.TIPO.eq(tipo(TipoLancamento.SALDO_ABERTURA))));
    }

    @Override
    public Cadeia cadeia(UUID dono, UUID lancamento) {
        UUID raiz = lancamento;
        int acima = 0;
        while (true) {
            UUID pai = banco.select(LANCAMENTO.ESTORNA_LANCAMENTO_ID).from(LANCAMENTO)
                    .where(LANCAMENTO.ID.eq(raiz), LANCAMENTO.DONO_ID.eq(dono)).fetchOne(LANCAMENTO.ESTORNA_LANCAMENTO_ID);
            if (pai == null) {
                break;
            }
            raiz = pai;
            acima++;
        }
        UUID ponta = lancamento;
        UUID operacao = operacaoDe(lancamento);
        int abaixo = 0;
        while (true) {
            var estorno = banco.select(LANCAMENTO.ID, LANCAMENTO.OPERACAO_ID).from(LANCAMENTO)
                    .where(LANCAMENTO.ESTORNA_LANCAMENTO_ID.eq(ponta)).fetchOne();
            if (estorno == null) {
                break;
            }
            ponta = estorno.value1();
            operacao = estorno.value2();
            abaixo++;
        }
        return new Cadeia(raiz, ponta, acima + abaixo, abaixo, operacao);
    }

    @Override
    public boolean inserirChaveFiscal(OperacaoEmCurso operacao, String chave, UUID lancamento) {
        return banco.insertInto(CHAVE_FISCAL).set(CHAVE_FISCAL.DONO_ID, operacao.dono()).set(CHAVE_FISCAL.CHAVE_NFCE, chave)
                .set(CHAVE_FISCAL.LANCAMENTO_ID, lancamento)
                .onConflictDoNothing()
                .execute() == 1;
    }

    @Override
    public Optional<Resumo> lancamentoDaChaveFiscal(UUID dono, String chave) {
        return banco.select(REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO).from(CHAVE_FISCAL)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(CHAVE_FISCAL.LANCAMENTO_ID))
                .join(REFERENCIA_CURTA).on(REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()))
                .where(CHAVE_FISCAL.DONO_ID.eq(dono), CHAVE_FISCAL.CHAVE_NFCE.eq(chave), vigente())
                .fetchOptional(r -> new Resumo(r.value1(), r.value2()));
    }

    @Override
    public Optional<UUID> lancamentoApontadoPelaChave(UUID dono, String chave) {
        return banco.select(CHAVE_FISCAL.LANCAMENTO_ID).from(CHAVE_FISCAL)
                .where(CHAVE_FISCAL.DONO_ID.eq(dono), CHAVE_FISCAL.CHAVE_NFCE.eq(chave))
                .fetchOptional(CHAVE_FISCAL.LANCAMENTO_ID);
    }

    @Override
    public Optional<String> chaveDoLancamento(UUID dono, UUID lancamento) {
        return banco.select(CHAVE_FISCAL.CHAVE_NFCE).from(CHAVE_FISCAL)
                .where(CHAVE_FISCAL.DONO_ID.eq(dono), CHAVE_FISCAL.LANCAMENTO_ID.eq(lancamento))
                .fetchOptional(CHAVE_FISCAL.CHAVE_NFCE);
    }

    @Override
    public void moverChaveFiscal(OperacaoEmCurso operacao, String chave, UUID lancamento) {
        banco.update(CHAVE_FISCAL).set(CHAVE_FISCAL.LANCAMENTO_ID, lancamento)
                .where(CHAVE_FISCAL.DONO_ID.eq(operacao.dono()), CHAVE_FISCAL.CHAVE_NFCE.eq(chave))
                .execute();
    }

    @Override
    public List<Resumo> parecidos(UUID dono, TipoLancamento tipo, LocalDate data, UUID meio, UUID conta, long valorCentavos,
            int minutos) {
        OffsetDateTime desde = OffsetDateTime.now().minusMinutes(minutos);
        return banco.select(REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO).from(LANCAMENTO)
                .join(MOVIMENTO).on(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID), MOVIMENTO.CONTA_ID.eq(conta))
                .join(REFERENCIA_CURTA).on(REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()))
                .where(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.TIPO.eq(tipo(tipo)), LANCAMENTO.DATA.eq(data),
                        LANCAMENTO.REGISTRADO_EM.ge(desde), LANCAMENTO.MEIO_PAGAMENTO_ID.isNotDistinctFrom(meio),
                        vigente())
                .groupBy(REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO)
                .having(DSL.sum(MOVIMENTO.VALOR_CENTAVOS).eq(BigDecimal.valueOf(valorCentavos)))
                .fetch(r -> new Resumo(r.value1(), r.value2()));
    }

    @Override
    public long saldoCentavos(UUID conta) {
        BigDecimal saldo = banco.select(DSL.sum(MOVIMENTO.VALOR_CENTAVOS)).from(MOVIMENTO)
                .where(MOVIMENTO.CONTA_ID.eq(conta)).fetchOne(0, BigDecimal.class);
        return saldo == null ? 0 : saldo.longValueExact();
    }

    @Override
    public Map<UUID, Long> saldosCentavos(UUID dono) {
        return banco.select(MOVIMENTO.CONTA_ID, DSL.sum(MOVIMENTO.VALOR_CENTAVOS)).from(MOVIMENTO)
                .join(CONTA).on(CONTA.ID.eq(MOVIMENTO.CONTA_ID))
                .where(CONTA.DONO_ID.eq(dono))
                .groupBy(MOVIMENTO.CONTA_ID)
                .stream().collect(Collectors.toMap(r -> r.value1(), r -> r.value2().longValueExact()));
    }

    @Override
    public Map<UUID, Long> saldosCentavosAte(UUID dono, LocalDate ate) {
        return banco.select(MOVIMENTO.CONTA_ID, DSL.sum(MOVIMENTO.VALOR_CENTAVOS)).from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .where(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.DATA.le(ate))
                .groupBy(MOVIMENTO.CONTA_ID)
                .stream().collect(Collectors.toMap(r -> r.value1(), r -> r.value2().longValueExact()));
    }

    @Override
    public Map<Currency, CalculoConversao.Cotacao> cotacoes(UUID dono, LocalDate ate) {
        Map<Currency, CalculoConversao.Cotacao> cotacoes = new HashMap<>();
        banco.select(LANCAMENTO.MOEDA_ORIGINAL, LANCAMENTO.COTACAO, LANCAMENTO.DATA_COTACAO, REFERENCIA_CURTA.CODIGO).from(LANCAMENTO)
                .join(REFERENCIA_CURTA).on(REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()))
                .where(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.MOEDA_ORIGINAL.isNotNull(), LANCAMENTO.DATA_COTACAO.le(ate), naoEstorno(), vigente())
                .orderBy(LANCAMENTO.DATA_COTACAO.desc(), LANCAMENTO.REGISTRADO_EM.desc(), LANCAMENTO.ID.desc())
                .forEach(r -> cotacoes.putIfAbsent(Currency.getInstance(r.value1()),
                        new CalculoConversao.Cotacao(r.value2(), r.value3(), r.value4())));
        return cotacoes;
    }

    @Override
    public List<Linha> listar(UUID dono, Filtro f, int limite) {
        Field<BigDecimal> total = DSL.select(DSL.coalesce(DSL.sum(MOVIMENTO.VALOR_CENTAVOS), BigDecimal.ZERO)).from(MOVIMENTO)
                .where(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID), MOVIMENTO.VALOR_CENTAVOS.gt(0L),
                        MOVIMENTO.MOEDA.isDistinctFrom(LANCAMENTO.MOEDA_ORIGINAL)).asField();
        List<Condition> condicoes = new ArrayList<>();
        condicoes.add(LANCAMENTO.DONO_ID.eq(dono));
        if (!f.incluirAnulados()) {
            condicoes.add(LANCAMENTO.TIPO.ne(tipo(TipoLancamento.ESTORNO)));
            condicoes.add(vigente());
        }
        if (f.de() != null) {
            condicoes.add(LANCAMENTO.DATA.ge(f.de()));
        }
        if (f.ate() != null) {
            condicoes.add(LANCAMENTO.DATA.le(f.ate()));
        }
        if (f.contas() != null) {
            condicoes.add(DSL.exists(DSL.selectOne().from(MOVIMENTO).where(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID),
                    MOVIMENTO.CONTA_ID.in(f.contas()))).or(LANCAMENTO.CONTA_HISTORICA_ID.in(f.contas())));
        }
        if (f.procedencia() != null) {
            condicoes.add(f.procedencia() == Procedencia.EXOGENO ? exogeno() : DSL.not(exogeno()));
        }
        if (f.meio() != null) {
            condicoes.add(LANCAMENTO.MEIO_PAGAMENTO_ID.eq(f.meio()));
        }
        if (f.texto() != null) {
            condicoes.add(LANCAMENTO.DESCRICAO.containsIgnoreCase(f.texto())
                    .or(LANCAMENTO.CONTRAPARTE.containsIgnoreCase(f.texto()))
                    .or(LANCAMENTO.OBSERVACAO.containsIgnoreCase(f.texto())));
        }
        if (f.valorMinimo() != null) {
            condicoes.add(total.ge(BigDecimal.valueOf(f.valorMinimo())));
        }
        if (f.valorMaximo() != null) {
            condicoes.add(total.le(BigDecimal.valueOf(f.valorMaximo())));
        }
        if (f.depoisDe() != null) {
            condicoes.add(DSL.row(LANCAMENTO.DATA, LANCAMENTO.REGISTRADO_EM, LANCAMENTO.ID)
                    .lessThan(f.depoisDe().data(), f.depoisDe().registradoEm(), f.depoisDe().id()));
        }
        return linhas(condicoes, limite);
    }

    @Override
    public Optional<Linha> linha(UUID dono, UUID id) {
        return linhas(List.of(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.ID.eq(id)), 1).stream().findFirst();
    }

    @Override
    public List<FatoPeriodo> fatosDoPeriodo(UUID dono, LocalDate de, LocalDate ate, UUID ambito) {
        return fatos(dono, de, ate, ambito, DSL.noCondition());
    }

    @Override
    public List<FatoPeriodo> fatosForaDoCredito(UUID dono, LocalDate de, LocalDate ate, UUID ambito) {
        var noCartao = MOVIMENTO.as("no_cartao");
        return fatos(dono, de, ate, ambito, DSL.not(exogeno()).and(DSL.notExists(DSL.selectOne().from(noCartao)
                .where(noCartao.LANCAMENTO_ID.eq(LANCAMENTO.ID), noCartao.FATURA_ID.isNotNull()))));
    }

    private List<FatoPeriodo> fatos(UUID dono, LocalDate de, LocalDate ate, UUID ambito, Condition extra) {
        List<Condition> condicoes = new ArrayList<>(List.of(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.DATA.between(de, ate),
                LANCAMENTO.TIPO.in(tipo(TipoLancamento.GASTO), tipo(TipoLancamento.GANHO)), vigente(), extra));
        if (ambito != null) {
            condicoes.add(MOVIMENTO.AMBITO_ID.eq(ambito));
        }
        return banco.select(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.TIPO, LANCAMENTO.DATA, LANCAMENTO.DESCRICAO,
                        LANCAMENTO.CONTRAPARTE, LANCAMENTO.MEIO_PAGAMENTO_ID, MOVIMENTO.CONTA_ID, CONTA.CLASSE, MOVIMENTO.AMBITO_ID,
                        MOVIMENTO.VALOR_CENTAVOS, MOVIMENTO.MOEDA, LANCAMENTO.PROCEDENCIA)
                .from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .join(CONTA).on(CONTA.ID.eq(MOVIMENTO.CONTA_ID))
                .join(REFERENCIA_CURTA).on(REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()))
                .where(condicoes)
                .fetch(r -> new FatoPeriodo(r.value1(), r.value2(), dominio(r.value3()), r.value4(), r.value5(), r.value6(), r.value7(),
                        r.value8(), r.value9() == ClasseConta.ativo || r.value9() == ClasseConta.passivo || r.value9() == ClasseConta.patrimonio,
                        r.value10(), Dinheiro.deCentavos(r.value11(), Currency.getInstance(r.value12())),
                        r.value13() == ProcedenciaLancamento.exogeno));
    }

    private Dinheiro precoAVista(Long centavos, UUID lancamento) {
        return centavos == null ? null : Dinheiro.deCentavos(centavos, movimentos(lancamento).get(0).valor().moeda());
    }

    private static ValorOriginal original(Long unidades, String moeda, BigDecimal cotacao, LocalDate data) {
        return unidades == null ? null : ValorOriginal.deUnidadesMinimas(unidades, Currency.getInstance(moeda), cotacao, data);
    }

    private List<Linha> linhas(List<Condition> condicoes, int limite) {
        var estorno = LANCAMENTO.as("estorno");
        Field<String> estornaCodigo = codigoDe(LANCAMENTO.ESTORNA_LANCAMENTO_ID);
        Field<String> estornadoPor = codigoDe(DSL.select(estorno.ID).from(estorno)
                .where(estorno.ESTORNA_LANCAMENTO_ID.eq(LANCAMENTO.ID)).asField());
        Field<String> operacaoCodigo = DSL.select(REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.OPERACAO_ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.OPERACAO.entidade())).asField("operacao_codigo");
        Field<Boolean> anulado = Routines.lancamentoAnulado(LANCAMENTO.ID).as("anulado");
        var lidos = banco.select(LANCAMENTO.fields()).select(REFERENCIA_CURTA.CODIGO, anulado, estornaCodigo.as("estorna_codigo"),
                        estornadoPor.as("estornado_por"), operacaoCodigo)
                .from(LANCAMENTO)
                .join(REFERENCIA_CURTA).on(REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.ID),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()))
                .where(condicoes)
                .orderBy(LANCAMENTO.DATA.desc(), LANCAMENTO.REGISTRADO_EM.desc(), LANCAMENTO.ID.desc())
                .limit(limite)
                .fetch();
        Map<UUID, List<Movimento>> movimentos = movimentos(lidos.getValues(LANCAMENTO.ID));
        List<Linha> linhas = new ArrayList<>();
        for (Record r : lidos) {
            UUID id = r.get(LANCAMENTO.ID);
            linhas.add(new Linha(id, r.get(REFERENCIA_CURTA.CODIGO), dominio(r.get(LANCAMENTO.TIPO)), r.get(LANCAMENTO.DATA),
                    r.get(LANCAMENTO.DESCRICAO), r.get(LANCAMENTO.CONTRAPARTE), r.get(LANCAMENTO.OBSERVACAO),
                    r.get(LANCAMENTO.MEIO_PAGAMENTO_ID), r.get(LANCAMENTO.ORIGEM).getLiteral(),
                    Boolean.TRUE.equals(r.get("anulado", Boolean.class)), r.get("estorna_codigo", String.class),
                    r.get("estornado_por", String.class), r.get("operacao_codigo", String.class),
                    r.get(LANCAMENTO.REGISTRADO_EM), movimentos.getOrDefault(id, List.of()),
                    original(r.get(LANCAMENTO.VALOR_ORIGINAL_MINIMO), r.get(LANCAMENTO.MOEDA_ORIGINAL), r.get(LANCAMENTO.COTACAO),
                            r.get(LANCAMENTO.DATA_COTACAO)),
                    r.get(LANCAMENTO.PRECO_A_VISTA_CENTAVOS) == null ? null : precoAVista(r.get(LANCAMENTO.PRECO_A_VISTA_CENTAVOS), id),
                    procedencia(r.get(LANCAMENTO.PROCEDENCIA), dominio(r.get(LANCAMENTO.TIPO))), r.get(LANCAMENTO.CONTA_HISTORICA_ID)));
        }
        return linhas;
    }

    @Override
    public List<Ajuste> ajustesDoPeriodo(UUID dono, LocalDate de, LocalDate ate) {
        return banco.fetch("""
                select l.id, r.codigo, l.data, l.descricao, m.valor_centavos, m.moeda
                  from lancamento l
                  join movimento m on m.lancamento_id = l.id
                  join conta c on c.id = m.conta_id
                  left join referencia_curta r on r.entidade_id = l.id and r.tipo_entidade = 'lancamento'
                 where l.dono_id = ? and l.tipo = 'ajuste' and l.data between ? and ?
                   and c.classe <> 'patrimonio' and not lancamento_anulado(l.id)
                 order by l.data, l.registrado_em
                """, dono, de, ate).map(r -> new Ajuste(r.get(0, UUID.class), r.get(1, String.class), r.get(2, LocalDate.class),
                r.get(3, String.class), r.get(4, Long.class), r.get(5, String.class)));
    }

    private static Field<String> codigoDe(Field<UUID> lancamento) {
        return DSL.select(DSL.max(DSL.field("rc.codigo", String.class))).from(REFERENCIA_CURTA.as("rc"))
                .where(DSL.field("rc.entidade_id", UUID.class).eq(lancamento),
                        DSL.field("rc.tipo_entidade", String.class).eq(TipoReferencia.LANCAMENTO.entidade())).asField();
    }

    private Map<UUID, List<Movimento>> movimentos(Collection<UUID> lancamentos) {
        return banco.selectFrom(MOVIMENTO).where(MOVIMENTO.LANCAMENTO_ID.in(lancamentos)).orderBy(MOVIMENTO.ID).stream()
                .collect(Collectors.groupingBy(r -> r.getLancamentoId(), Collectors.mapping(
                        r -> new Movimento(r.getContaId(), Dinheiro.deCentavos(r.getValorCentavos(), Currency.getInstance(r.getMoeda())),
                                r.getAmbitoId(), r.getFaturaId(), r.getParcela() == null ? null : r.getParcela().intValue(),
                                r.getTotalParcelas() == null ? null : r.getTotalParcelas().intValue(), r.getFaturaAberturaId()), Collectors.toList())));
    }

    private List<Movimento> movimentos(UUID lancamento) {
        return movimentos(List.of(lancamento)).getOrDefault(lancamento, List.of());
    }

    @Override
    public List<ItemLancamento> itens(UUID lancamento) {
        return banco.selectFrom(ITEM_LANCAMENTO).where(ITEM_LANCAMENTO.LANCAMENTO_ID.eq(lancamento))
                .orderBy(ITEM_LANCAMENTO.POSICAO)
                .fetch(r -> new ItemLancamento(r.getDescricao(), r.getQuantidade(), r.getContaId(), r.getAmbitoId(),
                        Dinheiro.deCentavos(r.getValorCentavos(), Currency.getInstance(r.getMoeda()))));
    }

    @Override
    public Set<UUID> operacoesComConta(UUID conta) {
        return new HashSet<>(banco.selectDistinct(LANCAMENTO.OPERACAO_ID).from(LANCAMENTO)
                .join(MOVIMENTO).on(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID))
                .where(MOVIMENTO.CONTA_ID.eq(conta).or(LANCAMENTO.CONTA_HISTORICA_ID.eq(conta)), naoEstorno(), vigente()).fetch(LANCAMENTO.OPERACAO_ID));
    }

    @Override
    public Set<UUID> operacoesComMovimentos(UUID conta) {
        return new HashSet<>(banco.selectDistinct(LANCAMENTO.OPERACAO_ID).from(LANCAMENTO)
                .leftJoin(MOVIMENTO).on(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID))
                .where(MOVIMENTO.CONTA_ID.eq(conta).or(LANCAMENTO.CONTA_HISTORICA_ID.eq(conta))).fetch(LANCAMENTO.OPERACAO_ID));
    }

    @Override
    public Set<UUID> operacoesComAmbito(UUID ambito) {
        return new HashSet<>(banco.selectDistinct(LANCAMENTO.OPERACAO_ID).from(LANCAMENTO)
                .join(MOVIMENTO).on(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID))
                .where(MOVIMENTO.AMBITO_ID.eq(ambito), naoEstorno(), vigente()).fetch(LANCAMENTO.OPERACAO_ID));
    }

    @Override
    public Set<UUID> operacoesComMeio(UUID meio) {
        return new HashSet<>(banco.selectDistinct(LANCAMENTO.OPERACAO_ID).from(LANCAMENTO)
                .where(LANCAMENTO.MEIO_PAGAMENTO_ID.eq(meio), naoEstorno(), vigente()).fetch(LANCAMENTO.OPERACAO_ID));
    }

    @Override
    public UUID operacaoDe(UUID lancamento) {
        return banco.select(LANCAMENTO.OPERACAO_ID).from(LANCAMENTO).where(LANCAMENTO.ID.eq(lancamento))
                .fetchSingle(LANCAMENTO.OPERACAO_ID);
    }

    @Override
    public Map<UUID, String> codigos(Collection<UUID> lancamentos) {
        return banco.select(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.ENTIDADE_ID.in(lancamentos),
                        REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()))
                .stream().collect(Collectors.toMap(r -> r.value1(), r -> r.value2()));
    }

    private static Condition naoEstorno() {
        return LANCAMENTO.TIPO.ne(tipo(TipoLancamento.ESTORNO));
    }

    private static Condition vigente() {
        return DSL.not(DSL.condition(Routines.lancamentoAnulado(LANCAMENTO.ID)));
    }

    private static TipoLancamento dominio(tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento tipo) {
        return TipoLancamento.valueOf(tipo.name().toUpperCase(Locale.ROOT));
    }

    private static tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento tipo(TipoLancamento tipo) {
        return tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.valueOf(tipo.name().toLowerCase(Locale.ROOT));
    }
}
