package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CARTAO_CREDITO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EFEITO_OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.FATURA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.LANCAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MOVIMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.RETRATO;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.alicerce.financeiro.persistencia.jooq.enums.ClasseConta;
import tools.alicerce.financeiro.persistencia.jooq.enums.ProcedenciaLancamento;
import tools.alicerce.financeiro.persistencia.jooq.enums.TipoConta;
import tools.alicerce.financeiro.persistencia.jooq.enums.TipoLancamento;

/**
 * Testes de aceite contra o banco, com o usuário da aplicação: o livro-razão não aceita
 * UPDATE nem DELETE, e todo lançamento soma zero dentro de cada moeda no commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class LivroRazaoBancoTest {

    private static final String PERMISSAO_NEGADA = "42501";
    private static final String CHECK_VIOLADO = "23514";
    private static final String CHAVE_ESTRANGEIRA = "23503";

    @Autowired
    DSLContext banco;

    @Autowired
    Flyway flyway;

    private UUID dono;
    private UUID operacao;
    private UUID corrente;
    private UUID mercado;
    private UUID contaDolar;

    @BeforeEach
    void estrutura() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono)
                .set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString())
                .execute();
        operacao = UUID.randomUUID();
        banco.insertInto(OPERACAO).set(OPERACAO.ID, operacao).set(OPERACAO.DONO_ID, dono)
                .set(OPERACAO.CAPACIDADE, "teste").set(OPERACAO.ARGUMENTOS, JSONB.valueOf("{}"))
                .set(OPERACAO.CLIENTE, "teste")
                .execute();
        corrente = conta(ClasseConta.ativo, TipoConta.corrente, "Itaú", "BRL");
        mercado = conta(ClasseConta.despesa, TipoConta.categoria, "Mercado", "BRL");
        contaDolar = conta(ClasseConta.ativo, TipoConta.corrente, "Wise", "USD");
    }

    @Test
    void aplicacaoEntraComOUsuarioRestrito() {
        assertThat(banco.fetchValue("select current_user")).isEqualTo(PostgresDeTeste.USUARIO_APLICACAO);
    }

    @Test
    void lancamentoQueSomaZeroEntra() {
        assertThatCode(() -> banco.transaction(t -> {
            UUID id = lancamento(t.dsl());
            movimento(t.dsl(), id, mercado, "BRL", 4500);
            movimento(t.dsl(), id, corrente, "BRL", -4500);
        })).doesNotThrowAnyException();
    }

    @Test
    void lancamentoQueNaoSomaZeroEhRecusadoNoCommit() {
        Throwable erro = catchThrowable(() -> banco.transaction(t -> {
            UUID id = lancamento(t.dsl());
            movimento(t.dsl(), id, mercado, "BRL", 4500);
            movimento(t.dsl(), id, corrente, "BRL", -4000);
        }));
        assertThat(estadoSql(erro)).isEqualTo(CHECK_VIOLADO);
        assertThat(banco.fetchCount(LANCAMENTO, LANCAMENTO.DONO_ID.eq(dono))).isZero();
    }

    @Test
    void lancamentoQueSomaZeroNoTotalMasNaoEmCadaMoedaEhRecusado() {
        // 45,00 BRL de gasto pagos com 45,00 "USD": o total é zero, mas cada moeda não.
        Throwable erro = catchThrowable(() -> banco.transaction(t -> {
            UUID id = lancamento(t.dsl());
            movimento(t.dsl(), id, mercado, "BRL", 4500);
            movimento(t.dsl(), id, contaDolar, "USD", -4500);
        }));
        assertThat(estadoSql(erro)).isEqualTo(CHECK_VIOLADO);
    }

    @Test
    void lancamentoSemMovimentosEhRecusado() {
        assertThat(estadoSql(catchThrowable(() -> banco.transaction(t -> lancamento(t.dsl())))))
                .isEqualTo(CHECK_VIOLADO);
    }

    @Test
    void movimentoSoEntraEmContaDaMesmaMoeda() {
        Throwable erro = catchThrowable(() -> banco.transaction(t -> {
            UUID id = lancamento(t.dsl());
            movimento(t.dsl(), id, contaDolar, "BRL", 4500);
            movimento(t.dsl(), id, corrente, "BRL", -4500);
        }));
        assertThat(estadoSql(erro)).isEqualTo(CHAVE_ESTRANGEIRA);
    }

    @Test
    void livroRazaoNaoAceitaUpdateNemDelete() {
        UUID[] ids = new UUID[1];
        banco.transaction(t -> {
            ids[0] = lancamento(t.dsl());
            movimento(t.dsl(), ids[0], mercado, "BRL", 4500);
            movimento(t.dsl(), ids[0], corrente, "BRL", -4500);
        });
        UUID id = ids[0];

        assertThat(estadoSql(catchThrowable(() -> banco.update(LANCAMENTO)
                .set(LANCAMENTO.DESCRICAO, "alterado").where(LANCAMENTO.ID.eq(id)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(LANCAMENTO).where(LANCAMENTO.ID.eq(id)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.update(MOVIMENTO)
                .set(MOVIMENTO.VALOR_CENTAVOS, 1L).where(MOVIMENTO.LANCAMENTO_ID.eq(id)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(MOVIMENTO).where(MOVIMENTO.LANCAMENTO_ID.eq(id)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(banco.fetchCount(MOVIMENTO, MOVIMENTO.LANCAMENTO_ID.eq(id))).isEqualTo(2);
    }

    @Test
    void lancamentoNovoDeclaraAProcedenciaEContaComSaldoTemMarco() {
        Throwable semProcedencia = catchThrowable(() -> banco.insertInto(LANCAMENTO).set(LANCAMENTO.ID, UUID.randomUUID()).set(LANCAMENTO.DONO_ID, dono)
                .set(LANCAMENTO.OPERACAO_ID, operacao).set(LANCAMENTO.TIPO, TipoLancamento.gasto).set(LANCAMENTO.DATA, LocalDate.now())
                .set(LANCAMENTO.DESCRICAO, "sem procedência").execute());
        Throwable semMarco = catchThrowable(() -> banco.insertInto(CONTA).set(CONTA.ID, UUID.randomUUID()).set(CONTA.DONO_ID, dono)
                .set(CONTA.CLASSE, ClasseConta.ativo).set(CONTA.TIPO, TipoConta.corrente).set(CONTA.NOME, "Sem marco").set(CONTA.MOEDA, "BRL").execute());

        assertThat(estadoSql(semProcedencia)).isEqualTo(CHECK_VIOLADO);
        assertThat(estadoSql(semMarco)).isEqualTo(CHECK_VIOLADO);
    }

    @Test
    void parcelaForaDeFaturaSoExisteEmSaldoDeAbertura() {
        UUID tecnica = conta(ClasseConta.patrimonio, TipoConta.saldo_abertura, "Saldo de abertura", "BRL");

        banco.transaction(c -> {
            UUID l = lancamento(c.dsl());
            movimento(c.dsl(), l, mercado, "BRL", 100);
            parcela(c.dsl(), l, tecnica, -100);
        });
        Throwable naCorrente = catchThrowable(() -> banco.transaction(c -> {
            UUID l = lancamento(c.dsl());
            movimento(c.dsl(), l, mercado, "BRL", 100);
            parcela(c.dsl(), l, corrente, -100);
        }));

        assertThat(estadoSql(naCorrente)).isEqualTo(CHECK_VIOLADO);
    }

    private static void parcela(DSLContext t, UUID lancamento, UUID conta, long centavos) {
        t.insertInto(MOVIMENTO).set(MOVIMENTO.ID, UUID.randomUUID()).set(MOVIMENTO.LANCAMENTO_ID, lancamento).set(MOVIMENTO.CONTA_ID, conta)
                .set(MOVIMENTO.MOEDA, "BRL").set(MOVIMENTO.VALOR_CENTAVOS, centavos).set(MOVIMENTO.PARCELA, (short) 1).set(MOVIMENTO.TOTAL_PARCELAS, (short) 2)
                .execute();
    }

    @Test
    void movimentoDeCartaoExigeFaturaDoProprioCartao() {
        UUID cartao = cartaoComFatura("2026-09");
        UUID outro = cartaoComFatura("2026-09");
        UUID faturaDoOutro = banco.select(FATURA.ID).from(FATURA).where(FATURA.CARTAO_ID.eq(outro)).fetchSingle(FATURA.ID);

        Throwable semFatura = catchThrowable(() -> banco.transaction(c -> {
            UUID l = lancamento(c.dsl());
            movimento(c.dsl(), l, mercado, "BRL", 100);
            movimento(c.dsl(), l, cartao, "BRL", -100);
        }));
        assertThat(estadoSql(semFatura)).isEqualTo(CHECK_VIOLADO);

        Throwable faturaErrada = catchThrowable(() -> banco.transaction(c -> {
            UUID l = lancamento(c.dsl());
            movimento(c.dsl(), l, mercado, "BRL", 100);
            c.dsl().insertInto(MOVIMENTO).set(MOVIMENTO.ID, UUID.randomUUID()).set(MOVIMENTO.LANCAMENTO_ID, l).set(MOVIMENTO.CONTA_ID, cartao)
                    .set(MOVIMENTO.MOEDA, "BRL").set(MOVIMENTO.VALOR_CENTAVOS, -100L).set(MOVIMENTO.FATURA_ID, faturaDoOutro).execute();
        }));
        assertThat(estadoSql(faturaErrada)).isEqualTo(CHAVE_ESTRANGEIRA);

        Throwable faturaForaDoCartao = catchThrowable(() -> banco.transaction(c -> {
            UUID l = lancamento(c.dsl());
            c.dsl().insertInto(MOVIMENTO).set(MOVIMENTO.ID, UUID.randomUUID()).set(MOVIMENTO.LANCAMENTO_ID, l).set(MOVIMENTO.CONTA_ID, corrente)
                    .set(MOVIMENTO.MOEDA, "BRL").set(MOVIMENTO.VALOR_CENTAVOS, -100L).set(MOVIMENTO.FATURA_ID, faturaDoOutro).execute();
            movimento(c.dsl(), l, mercado, "BRL", 100);
        }));
        assertThat(estadoSql(faturaForaDoCartao)).isEqualTo(CHAVE_ESTRANGEIRA);
    }

    @Test
    void faturaNaoAceitaUpdateNemDelete() {
        cartaoComFatura("2026-09");

        assertThat(estadoSql(catchThrowable(() -> banco.update(FATURA).set(FATURA.PERIODO, "2026-10").execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(FATURA).execute()))).isEqualTo(PERMISSAO_NEGADA);
    }

    private UUID cartaoComFatura(String periodo) {
        UUID cartao = conta(ClasseConta.passivo, TipoConta.cartao_credito, "Cartão " + UUID.randomUUID(), "BRL");
        banco.insertInto(CARTAO_CREDITO).set(CARTAO_CREDITO.CONTA_ID, cartao).set(CARTAO_CREDITO.DIA_FECHAMENTO, (short) 10)
                .set(CARTAO_CREDITO.DIA_VENCIMENTO, (short) 17).execute();
        banco.insertInto(FATURA).set(FATURA.ID, UUID.randomUUID()).set(FATURA.DONO_ID, dono).set(FATURA.CARTAO_ID, cartao).set(FATURA.PERIODO, periodo)
                .set(FATURA.FECHA_EM, LocalDate.parse(periodo + "-10")).set(FATURA.VENCE_EM, LocalDate.parse(periodo + "-17")).execute();
        return cartao;
    }

    @Test
    void retratoNaoAceitaUpdateNemDelete() {
        assertThat(estadoSql(catchThrowable(() -> banco.update(RETRATO).set(RETRATO.CAPACIDADE, "outra").execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(RETRATO).execute()))).isEqualTo(PERMISSAO_NEGADA);
    }

    @Test
    void moedaOriginalSoEntraCompletaELancamentoAntigoFicaSemEla() {
        UUID[] ids = new UUID[1];
        banco.transaction(t -> {
            ids[0] = lancamento(t.dsl());
            movimento(t.dsl(), ids[0], mercado, "BRL", 4500);
            movimento(t.dsl(), ids[0], corrente, "BRL", -4500);
        });
        UUID semMoeda = ids[0];
        assertThat(banco.select(LANCAMENTO.MOEDA_ORIGINAL).from(LANCAMENTO).where(LANCAMENTO.ID.eq(semMoeda)).fetchSingle().value1()).isNull();

        assertThat(catchThrowable(() -> banco.insertInto(LANCAMENTO).set(LANCAMENTO.ID, UUID.randomUUID()).set(LANCAMENTO.DONO_ID, dono)
                .set(LANCAMENTO.OPERACAO_ID, operacao).set(LANCAMENTO.TIPO, TipoLancamento.gasto).set(LANCAMENTO.DATA, LocalDate.now())
                .set(LANCAMENTO.DESCRICAO, "pela metade").set(LANCAMENTO.MOEDA_ORIGINAL, "USD")
                .set(LANCAMENTO.PROCEDENCIA, ProcedenciaLancamento.endogeno).execute()))
                .hasMessageContaining("lancamento_moeda_original_completa");
    }

    @Test
    void operacaoSoMudaDeEstadoEEfeitosSoRecebemInsert() {
        assertThatCode(() -> banco.update(OPERACAO).set(OPERACAO.ESTADO, "desfeita")
                .where(OPERACAO.ID.eq(operacao)).execute()).doesNotThrowAnyException();
        assertThat(estadoSql(catchThrowable(() -> banco.update(OPERACAO).set(OPERACAO.CAPACIDADE, "outra")
                .where(OPERACAO.ID.eq(operacao)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(OPERACAO).where(OPERACAO.ID.eq(operacao)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(EFEITO_OPERACAO)
                .where(EFEITO_OPERACAO.OPERACAO_ID.eq(operacao)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
    }

    @Test
    void privilegiosVoltamSozinhosDepoisDeUmaRestauracao() throws Exception {
        // O backup é feito com --no-privileges: o banco restaurado chega sem os GRANTs da V4.
        try (Connection dono = flyway.getConfiguration().getDataSource().getConnection();
                Statement comando = dono.createStatement()) {
            comando.execute("revoke all on all tables in schema public from financeiro_app");
        }
        assertThat(estadoSql(catchThrowable(() -> banco.fetchCount(CONTA)))).isEqualTo(PERMISSAO_NEGADA);

        flyway.migrate();

        assertThat(banco.fetchCount(CONTA, CONTA.DONO_ID.eq(dono))).isEqualTo(3);
        assertThat(estadoSql(catchThrowable(() -> banco.deleteFrom(LANCAMENTO).where(LANCAMENTO.DONO_ID.eq(dono)).execute())))
                .isEqualTo(PERMISSAO_NEGADA);
    }

    private UUID conta(ClasseConta classe, TipoConta tipo, String nome, String moeda) {
        UUID id = UUID.randomUUID();
        banco.insertInto(CONTA).set(CONTA.ID, id).set(CONTA.DONO_ID, dono)
                .set(CONTA.CLASSE, classe).set(CONTA.TIPO, tipo).set(CONTA.NOME, nome).set(CONTA.MOEDA, moeda)
                .set(CONTA.MARCO_INICIAL, classe == ClasseConta.ativo || classe == ClasseConta.passivo ? LocalDate.of(2026, 1, 1) : null)
                .execute();
        return id;
    }

    private UUID lancamento(DSLContext t) {
        UUID id = UUID.randomUUID();
        t.insertInto(LANCAMENTO).set(LANCAMENTO.ID, id).set(LANCAMENTO.DONO_ID, dono)
                .set(LANCAMENTO.OPERACAO_ID, operacao).set(LANCAMENTO.TIPO, TipoLancamento.gasto)
                .set(LANCAMENTO.DATA, LocalDate.of(2026, 10, 29)).set(LANCAMENTO.DESCRICAO, "Mercado")
                .set(LANCAMENTO.PROCEDENCIA, ProcedenciaLancamento.endogeno)
                .execute();
        return id;
    }

    private static void movimento(DSLContext t, UUID lancamento, UUID conta, String moeda, long centavos) {
        t.insertInto(MOVIMENTO).set(MOVIMENTO.ID, UUID.randomUUID()).set(MOVIMENTO.LANCAMENTO_ID, lancamento)
                .set(MOVIMENTO.CONTA_ID, conta).set(MOVIMENTO.MOEDA, moeda).set(MOVIMENTO.VALOR_CENTAVOS, centavos)
                .execute();
    }

    /** O SQLState da falha do Postgres, onde quer que o Spring ou o jOOQ a tenham embrulhado. */
    private static String estadoSql(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("Sem SQLException na cadeia", erro);
    }
}
