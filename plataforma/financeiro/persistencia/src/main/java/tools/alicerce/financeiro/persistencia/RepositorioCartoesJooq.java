package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.CARTAO_CREDITO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.FATURA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MOVIMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioCartoes;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.CompraNoCredito;
import tools.alicerce.financeiro.dominio.CompraNoCredito.Lado;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.persistencia.jooq.Routines;
import tools.alicerce.financeiro.persistencia.jooq.enums.ClasseConta;

/** Persistência dos cartões e das faturas. A fatura só recebe INSERT: a situação é derivada. */
@Repository
class RepositorioCartoesJooq implements RepositorioCartoes {

    private static final Field<Long> SOMA = DSL.sum(MOVIMENTO.VALOR_CENTAVOS).cast(Long.class);

    private final DSLContext banco;

    RepositorioCartoesJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public List<CartaoCredito> cartoes(UUID dono) {
        return banco.select(CARTAO_CREDITO.fields()).from(CARTAO_CREDITO).join(CONTA).on(CONTA.ID.eq(CARTAO_CREDITO.CONTA_ID))
                .where(CONTA.DONO_ID.eq(dono), CONTA.ARQUIVADO_EM.isNull()).orderBy(CONTA.NOME)
                .fetch(RepositorioCartoesJooq::cartao);
    }

    @Override
    public Optional<CartaoCredito> cartao(UUID conta) {
        return banco.selectFrom(CARTAO_CREDITO).where(CARTAO_CREDITO.CONTA_ID.eq(conta)).fetchOptional(RepositorioCartoesJooq::cartao);
    }

    private static CartaoCredito cartao(Record r) {
        return new CartaoCredito(r.get(CARTAO_CREDITO.CONTA_ID), r.get(CARTAO_CREDITO.DIA_FECHAMENTO), r.get(CARTAO_CREDITO.DIA_VENCIMENTO),
                r.get(CARTAO_CREDITO.LIMITE_CENTAVOS), r.get(CARTAO_CREDITO.CONTA_PAGAMENTO_ID), r.get(CARTAO_CREDITO.VERSAO));
    }

    @Override
    public void inserir(OperacaoEmCurso operacao, CartaoCredito c) {
        banco.insertInto(CARTAO_CREDITO).set(CARTAO_CREDITO.CONTA_ID, c.conta()).set(CARTAO_CREDITO.DIA_FECHAMENTO, (short) c.diaFechamento())
                .set(CARTAO_CREDITO.DIA_VENCIMENTO, (short) c.diaVencimento()).set(CARTAO_CREDITO.LIMITE_CENTAVOS, c.limiteCentavos())
                .set(CARTAO_CREDITO.CONTA_PAGAMENTO_ID, c.contaPagamento()).set(CARTAO_CREDITO.VERSAO, c.versao())
                .execute();
    }

    @Override
    public void atualizar(OperacaoEmCurso operacao, CartaoCredito c) {
        banco.update(CARTAO_CREDITO).set(CARTAO_CREDITO.DIA_FECHAMENTO, (short) c.diaFechamento())
                .set(CARTAO_CREDITO.DIA_VENCIMENTO, (short) c.diaVencimento()).set(CARTAO_CREDITO.LIMITE_CENTAVOS, c.limiteCentavos())
                .set(CARTAO_CREDITO.CONTA_PAGAMENTO_ID, c.contaPagamento()).set(CARTAO_CREDITO.VERSAO, c.versao())
                .where(CARTAO_CREDITO.CONTA_ID.eq(c.conta()))
                .execute();
    }

    @Override
    public Optional<Fatura> fatura(UUID cartao, String periodo) {
        return banco.selectFrom(FATURA).where(FATURA.CARTAO_ID.eq(cartao), FATURA.PERIODO.eq(periodo))
                .fetchOptional(RepositorioCartoesJooq::fatura);
    }

    @Override
    public Optional<Fatura> faturaPorId(UUID dono, UUID id) {
        return banco.selectFrom(FATURA).where(FATURA.ID.eq(id), FATURA.DONO_ID.eq(dono)).fetchOptional(RepositorioCartoesJooq::fatura);
    }

    @Override
    public List<Fatura> faturas(UUID cartao) {
        return banco.selectFrom(FATURA).where(FATURA.CARTAO_ID.eq(cartao)).orderBy(FATURA.VENCE_EM)
                .fetch(RepositorioCartoesJooq::fatura);
    }

    private static Fatura fatura(Record r) {
        return new Fatura(r.get(FATURA.ID), r.get(FATURA.CARTAO_ID), r.get(FATURA.PERIODO), r.get(FATURA.FECHA_EM), r.get(FATURA.VENCE_EM));
    }

    @Override
    public boolean inserirFatura(OperacaoEmCurso operacao, Fatura f) {
        return banco.insertInto(FATURA).set(FATURA.ID, f.id()).set(FATURA.DONO_ID, operacao.dono()).set(FATURA.CARTAO_ID, f.cartao())
                .set(FATURA.PERIODO, f.periodo()).set(FATURA.FECHA_EM, f.fechaEm()).set(FATURA.VENCE_EM, f.venceEm())
                .onConflictDoNothing()
                .execute() == 1;
    }

    @Override
    public Map<UUID, Totais> totais(UUID cartao) {
        Map<UUID, long[]> somas = new HashMap<>();
        banco.select(MOVIMENTO.FATURA_ID, LANCAMENTO.TIPO, SOMA).from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .where(MOVIMENTO.CONTA_ID.eq(cartao), MOVIMENTO.FATURA_ID.isNotNull(), vigente())
                .groupBy(MOVIMENTO.FATURA_ID, LANCAMENTO.TIPO)
                .forEach(r -> {
                    long[] soma = somas.computeIfAbsent(r.value1(), k -> new long[2]);
                    if (r.value2() == pagamentoFatura()) {
                        soma[1] += r.value3();
                    } else {
                        soma[0] -= r.value3();
                    }
                });
        Map<UUID, Totais> totais = new HashMap<>();
        somas.forEach((id, soma) -> totais.put(id, new Totais(soma[0], soma[1])));
        return totais;
    }

    @Override
    public List<ItemDaFatura> itens(UUID fatura) {
        return banco.select(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.TIPO, LANCAMENTO.DATA, LANCAMENTO.DESCRICAO,
                        MOVIMENTO.PARCELA, MOVIMENTO.TOTAL_PARCELAS, SOMA)
                .from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .join(REFERENCIA_CURTA).on(codigoDoLancamento())
                .where(MOVIMENTO.FATURA_ID.eq(fatura), LANCAMENTO.TIPO.ne(pagamentoFatura()), vigente())
                .groupBy(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.TIPO, LANCAMENTO.DATA, LANCAMENTO.DESCRICAO,
                        MOVIMENTO.PARCELA, MOVIMENTO.TOTAL_PARCELAS)
                .orderBy(LANCAMENTO.DATA, LANCAMENTO.ID)
                .fetch(r -> new ItemDaFatura(r.value1(), r.value2(), TipoLancamento.valueOf(r.value3().name().toUpperCase(Locale.ROOT)),
                        r.value4(), r.value5(), r.value6() == null ? null : r.value6() + "/" + r.value7(), -r.value8()));
    }

    @Override
    public List<Pagamento> pagamentos(Collection<UUID> faturas) {
        return banco.select(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO, LANCAMENTO.DATA, MOVIMENTO.VALOR_CENTAVOS,
                        MOVIMENTO.FATURA_ID, LANCAMENTO.OPERACAO_ID, LANCAMENTO.REGISTRADO_EM)
                .from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .join(REFERENCIA_CURTA).on(codigoDoLancamento())
                .where(MOVIMENTO.FATURA_ID.in(faturas), LANCAMENTO.TIPO.eq(pagamentoFatura()), vigente())
                .orderBy(LANCAMENTO.DATA, LANCAMENTO.ID)
                .fetch(r -> new Pagamento(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6(), r.value7(), r.value8()));
    }

    @Override
    public Optional<UUID> faturaDaAbertura(UUID cartao) {
        return banco.selectDistinct(MOVIMENTO.FATURA_ID).from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .where(MOVIMENTO.CONTA_ID.eq(cartao), LANCAMENTO.TIPO.eq(saldoAbertura()), vigente())
                .fetchOptional(MOVIMENTO.FATURA_ID);
    }

    @Override
    public List<ParcelaNaAbertura> parcelasNaAbertura(UUID fatura) {
        return banco.select(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO, MOVIMENTO.PARCELA, MOVIMENTO.TOTAL_PARCELAS, SOMA)
                .from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .join(REFERENCIA_CURTA).on(codigoDoLancamento())
                .where(MOVIMENTO.FATURA_ABERTURA_ID.eq(fatura), vigente())
                .groupBy(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO, MOVIMENTO.PARCELA, MOVIMENTO.TOTAL_PARCELAS)
                .orderBy(LANCAMENTO.DATA, LANCAMENTO.ID)
                .fetch(r -> new ParcelaNaAbertura(r.value1(), r.value2(), r.value3(), r.value4() == null ? null : r.value4() + "/" + r.value5(), -r.value6()));
    }

    @Override
    public List<CompraNoCredito> comprasComVencimento(UUID dono, LocalDate de, LocalDate ate) {
        var ids = DSL.selectDistinct(LANCAMENTO.ID).from(LANCAMENTO)
                .join(MOVIMENTO).on(MOVIMENTO.LANCAMENTO_ID.eq(LANCAMENTO.ID))
                .join(FATURA).on(FATURA.ID.eq(MOVIMENTO.FATURA_ID))
                .where(LANCAMENTO.DONO_ID.eq(dono), LANCAMENTO.TIPO.eq(gasto()), FATURA.VENCE_EM.between(de, ate), vigente());
        record Cabecalho(String codigo, String descricao, String contraparte, UUID meio) {}
        Map<UUID, Cabecalho> cabecalhos = new LinkedHashMap<>();
        Map<UUID, List<Lado>> categorias = new HashMap<>();
        Map<UUID, List<Lado>> parcelas = new HashMap<>();
        banco.select(LANCAMENTO.ID, REFERENCIA_CURTA.CODIGO, LANCAMENTO.DESCRICAO, LANCAMENTO.CONTRAPARTE, LANCAMENTO.MEIO_PAGAMENTO_ID,
                        MOVIMENTO.CONTA_ID, CONTA.CLASSE, MOVIMENTO.AMBITO_ID, MOVIMENTO.VALOR_CENTAVOS, MOVIMENTO.MOEDA, MOVIMENTO.PARCELA,
                        FATURA.VENCE_EM)
                .from(MOVIMENTO)
                .join(LANCAMENTO).on(LANCAMENTO.ID.eq(MOVIMENTO.LANCAMENTO_ID))
                .join(CONTA).on(CONTA.ID.eq(MOVIMENTO.CONTA_ID))
                .join(REFERENCIA_CURTA).on(codigoDoLancamento())
                .leftJoin(FATURA).on(FATURA.ID.eq(MOVIMENTO.FATURA_ID))
                .where(LANCAMENTO.ID.in(ids))
                .orderBy(LANCAMENTO.ID, MOVIMENTO.ID)
                .forEach(r -> {
                    UUID id = r.value1();
                    cabecalhos.putIfAbsent(id, new Cabecalho(r.value2(), r.value3(), r.value4(), r.value5()));
                    Dinheiro valor = Dinheiro.deCentavos(Math.abs(r.value9()), Currency.getInstance(r.value10()));
                    // Em compra em andamento, as parcelas fora do cartão estão em Saldo de abertura, sem vencimento.
                    boolean noCartao = r.value7() == ClasseConta.passivo || r.value7() == ClasseConta.patrimonio;
                    int parcela = !noCartao ? 0 : r.value11() == null ? 1 : r.value11();
                    (noCartao ? parcelas : categorias).computeIfAbsent(id, k -> new ArrayList<>())
                            .add(new Lado(r.value6(), r.value8(), valor, parcela, r.value12()));
                });
        List<CompraNoCredito> compras = new ArrayList<>();
        cabecalhos.forEach((id, c) -> compras.add(new CompraNoCredito(id, c.codigo(), c.descricao(), c.contraparte(), c.meio(),
                categorias.get(id), parcelas.get(id))));
        return compras;
    }

    private static Condition codigoDoLancamento() {
        return REFERENCIA_CURTA.ENTIDADE_ID.eq(LANCAMENTO.ID).and(REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.LANCAMENTO.entidade()));
    }

    /** Lançamento que vale: nem estorno, nem anulado. */
    private static Condition vigente() {
        return LANCAMENTO.TIPO.ne(tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.estorno)
                .and(DSL.not(DSL.condition(Routines.lancamentoAnulado(LANCAMENTO.ID))));
    }

    private static tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento saldoAbertura() {
        return tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.saldo_abertura;
    }

    private static tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento gasto() {
        return tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.gasto;
    }

    private static tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento pagamentoFatura() {
        return tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento.pagamento_fatura;
    }
}
