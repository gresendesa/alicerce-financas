package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EFEITO_OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EVENTO_AUDITORIA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.EntidadeVersionada;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.PedidoEscrita;
import tools.alicerce.financeiro.aplicacao.RegistradorOperacoes;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.alicerce.financeiro.persistencia.jooq.enums.ClasseConta;
import tools.alicerce.financeiro.persistencia.jooq.enums.TipoConta;

/**
 * Testes de aceite. A escrita de teste cria uma categoria direto no banco (sem passar
 * pelas capacidades de estrutura) e declara o efeito, como farão as capacidades.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class RegistradorOperacoesTest {

    /** O resultado de teste, gravado para a repetição idempotente. */
    record CategoriaCriada(UUID id, String referencia, String nome) {}

    @Autowired
    RegistradorOperacoes registrador;

    @Autowired
    DSLContext banco;

    private Chamador chamador;
    private final AtomicInteger execucoes = new AtomicInteger();

    @BeforeEach
    void dono() {
        UUID dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono)
                .set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString())
                .execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:structure"));
    }

    @Test
    void escritaGeraOperacaoComIdCurtoUrlEfeitosVersaoEAuditoria() {
        ResultadoEscrita<CategoriaCriada> resultado = criarCategoria("chave-1", "Mercado", "lote-1");

        assertThat(resultado.repetido()).isFalse();
        assertThat(resultado.operacao().referencia().id()).matches("o-[0-9A-HJKMNP-TV-Z]{6}");
        assertThat(resultado.operacao().url())
                .isEqualTo("https://app.alicerce.tools/fin/o/" + resultado.operacao().referencia().codigo());
        assertThat(resultado.valor().referencia()).startsWith("a-");

        UUID operacao = resultado.operacao().id();
        var gravada = banco.selectFrom(OPERACAO).where(OPERACAO.ID.eq(operacao)).fetchSingle();
        assertThat(gravada.getCapacidade()).isEqualTo("definir_conta");
        assertThat(gravada.getLote()).isEqualTo("lote-1");
        assertThat(gravada.getCliente()).isEqualTo("claude-ai");
        assertThat(gravada.getEstado()).isEqualTo("aplicada");
        assertThat(gravada.getArgumentos().data()).contains("\"nome\"", "Mercado");

        var efeito = banco.selectFrom(EFEITO_OPERACAO).where(EFEITO_OPERACAO.OPERACAO_ID.eq(operacao)).fetchSingle();
        assertThat(efeito.getOrdem()).isEqualTo((short) 1);
        assertThat(efeito.getTipo()).isEqualTo("entidade_criada");
        assertThat(efeito.getEntidade()).isEqualTo("conta");
        assertThat(efeito.getEntidadeId()).isEqualTo(resultado.valor().id());

        var versao = banco.selectFrom(CONTA_VERSAO).where(CONTA_VERSAO.CONTA_ID.eq(resultado.valor().id())).fetchSingle();
        assertThat(versao.getVersao()).isEqualTo(1L);
        assertThat(versao.getOperacaoId()).isEqualTo(operacao);

        var evento = banco.selectFrom(EVENTO_AUDITORIA).where(EVENTO_AUDITORIA.DONO_ID.eq(chamador.dono())).fetchSingle();
        assertThat(evento.getResultado()).isEqualTo("sucesso");
        assertThat(evento.getOperacaoId()).isEqualTo(operacao);
        assertThat(evento.getChaveIdempotencia()).isEqualTo("chave-1");
        assertThat(evento.getEscopos()).containsExactly("fin:read", "fin:structure");

        assertThat(EscritasRegistradas.semEfeito(banco, chamador.dono())).isEmpty();
    }

    @Test
    void efeitosFicamNaOrdemEmQueAconteceram() {
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        var resultado = registrador.registrar(chamador, pedido("chave-ordem", Map.of("nome", "duas")), String.class, op -> {
            inserirCategoria(op, primeira, "Lazer");
            inserirCategoria(op, segunda, "Cinema");
            return "ok";
        });
        assertThat(banco.select(EFEITO_OPERACAO.ENTIDADE_ID).from(EFEITO_OPERACAO)
                .where(EFEITO_OPERACAO.OPERACAO_ID.eq(resultado.operacao().id()))
                .orderBy(EFEITO_OPERACAO.ORDEM).fetch(EFEITO_OPERACAO.ENTIDADE_ID))
                .containsExactly(primeira, segunda);
    }

    @Test
    void mesmaChaveEMesmosArgumentosDevolveOResultadoOriginalSemGravarDeNovo() {
        var primeira = criarCategoria("chave-2", "Farmácia", null);
        var segunda = criarCategoria("chave-2", "Farmácia", null);

        assertThat(segunda.repetido()).isTrue();
        assertThat(segunda.valor()).isEqualTo(primeira.valor());
        assertThat(segunda.operacao()).isEqualTo(primeira.operacao());
        assertThat(execucoes).hasValue(1);
        assertThat(banco.fetchCount(CONTA, CONTA.DONO_ID.eq(chamador.dono()))).isEqualTo(1);
        assertThat(banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(chamador.dono()))).isEqualTo(1);
    }

    @Test
    void mesmaChaveComOutrosArgumentosEhConflitoIdempotencia() {
        criarCategoria("chave-3", "Padaria", null);

        assertThatThrownBy(() -> criarCategoria("chave-3", "Açougue", null))
                .isInstanceOfSatisfying(ErroTipado.class, e -> {
                    assertThat(e.codigo()).isEqualTo(CodigoErro.CONFLITO_IDEMPOTENCIA);
                    assertThat(e.campo()).isEqualTo("chave_idempotencia");
                });
        assertThat(banco.fetchCount(CONTA, CONTA.DONO_ID.eq(chamador.dono()))).isEqualTo(1);
        assertThat(banco.select(EVENTO_AUDITORIA.CODIGO_ERRO).from(EVENTO_AUDITORIA)
                .where(EVENTO_AUDITORIA.DONO_ID.eq(chamador.dono()), EVENTO_AUDITORIA.RESULTADO.eq("erro"))
                .fetch(EVENTO_AUDITORIA.CODIGO_ERRO))
                .containsExactly("conflito_idempotencia");
    }

    @Test
    void chamadasSimultaneasComAMesmaChaveGravamUmaVez() throws Exception {
        CountDownLatch primeiraDentro = new CountDownLatch(1);
        var primeira = CompletableFuture.supplyAsync(() -> registrador.registrar(chamador,
                pedido("chave-4", Map.of("nome", "Feira", "tipo", "categoria")), CategoriaCriada.class, op -> {
                    primeiraDentro.countDown();
                    pausa();
                    return inserirCategoria(op, UUID.randomUUID(), "Feira");
                }));
        assertThat(primeiraDentro.await(10, TimeUnit.SECONDS)).isTrue();
        var segunda = criarCategoria("chave-4", "Feira", null);

        assertThat(segunda.repetido()).isTrue();
        assertThat(segunda.operacao()).isEqualTo(primeira.get(10, TimeUnit.SECONDS).operacao());
        assertThat(execucoes).hasValue(0);
        assertThat(banco.fetchCount(CONTA, CONTA.DONO_ID.eq(chamador.dono()))).isEqualTo(1);
    }

    @Test
    void falhaNaEscritaDesfazTudoEAuditaOErro() {
        assertThatThrownBy(() -> registrador.registrar(chamador, pedido("chave-5", Map.of("nome", "x")), String.class, op -> {
            inserirCategoria(op, UUID.randomUUID(), "Transporte");
            throw ErroTipado.validacao("nome", "Nome inválido");
        })).isInstanceOf(ErroTipado.class);

        assertThat(banco.fetchCount(CONTA, CONTA.DONO_ID.eq(chamador.dono()))).isZero();
        assertThat(banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(chamador.dono()))).isZero();
        var evento = banco.selectFrom(EVENTO_AUDITORIA).where(EVENTO_AUDITORIA.DONO_ID.eq(chamador.dono())).fetchSingle();
        assertThat(evento.getResultado()).isEqualTo("erro");
        assertThat(evento.getCodigoErro()).isEqualTo("validacao_falhou");
        assertThat(evento.getOperacaoId()).isNull();
    }

    @Test
    void escritaSemEfeitoDeclaradoEhRecusada() {
        assertThatThrownBy(() -> registrador.registrar(chamador, pedido("chave-6", Map.of()), String.class, op -> "nada"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(banco.fetchCount(OPERACAO, OPERACAO.DONO_ID.eq(chamador.dono()))).isZero();
        assertThat(banco.select(EVENTO_AUDITORIA.CODIGO_ERRO).from(EVENTO_AUDITORIA)
                .where(EVENTO_AUDITORIA.DONO_ID.eq(chamador.dono())).fetchSingle(EVENTO_AUDITORIA.CODIGO_ERRO))
                .isEqualTo("erro_interno");
    }

    @Test
    void conferenciaNoBancoAchaEscritaFeitaPorFora() {
        criarCategoria("chave-7", "Saúde", null);
        UUID porFora = UUID.randomUUID();
        banco.insertInto(CONTA).set(CONTA.ID, porFora).set(CONTA.DONO_ID, chamador.dono())
                .set(CONTA.CLASSE, ClasseConta.despesa).set(CONTA.TIPO, TipoConta.categoria)
                .set(CONTA.NOME, "Por fora").set(CONTA.MOEDA, "BRL")
                .execute();

        assertThat(EscritasRegistradas.semEfeito(banco, chamador.dono())).containsExactly("conta " + porFora);
    }

    private ResultadoEscrita<CategoriaCriada> criarCategoria(String chave, String nome, String lote) {
        return registrador.registrar(chamador,
                new PedidoEscrita("definir_conta", Map.of("nome", nome, "tipo", "categoria"), ChaveIdempotencia.ler(chave), lote),
                CategoriaCriada.class,
                op -> {
                    execucoes.incrementAndGet();
                    return inserirCategoria(op, UUID.randomUUID(), nome);
                });
    }

    private CategoriaCriada inserirCategoria(OperacaoEmCurso op, UUID id, String nome) {
        banco.insertInto(CONTA).set(CONTA.ID, id).set(CONTA.DONO_ID, op.dono())
                .set(CONTA.CLASSE, ClasseConta.despesa).set(CONTA.TIPO, TipoConta.categoria)
                .set(CONTA.NOME, nome).set(CONTA.MOEDA, "BRL")
                .execute();
        op.entidadeCriada(EntidadeVersionada.CONTA, id, Map.of("nome", nome, "tipo", "categoria"));
        return new CategoriaCriada(id, op.referenciar(TipoReferencia.CONTA, id).id(), nome);
    }

    private static PedidoEscrita pedido(String chave, Map<String, ?> argumentos) {
        return new PedidoEscrita("definir_conta", argumentos, ChaveIdempotencia.ler(chave), null);
    }

    private static void pausa() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
