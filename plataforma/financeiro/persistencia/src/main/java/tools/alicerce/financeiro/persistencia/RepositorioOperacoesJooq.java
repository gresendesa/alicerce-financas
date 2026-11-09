package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.AMBITO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CARTAO_CREDITO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.COMPROMISSO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.META_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.EFEITO_OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.INSTITUICAO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MEIO_PAGAMENTO_VERSAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.OPERACAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.RESULTADO_OPERACAO;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.EntidadeVersionada;
import tools.alicerce.financeiro.aplicacao.PedidoEscrita;
import tools.alicerce.financeiro.aplicacao.RepositorioOperacoes;
import tools.alicerce.financeiro.aplicacao.TipoEfeito;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Persistência do registrador. É a única classe, além da criação do dono e da auditoria, que escreve
 * no banco sem receber uma operação em curso: ela é quem grava a operação.
 */
@Repository
class RepositorioOperacoesJooq implements RepositorioOperacoes {

    // 32^6 ≈ 10^9 códigos: colisões são raras, mas possíveis; sorteia de novo.
    private static final int TENTATIVAS_REFERENCIA = 10;

    private final DSLContext banco;
    private final SecureRandom aleatorio = new SecureRandom();

    RepositorioOperacoesJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public boolean inserir(UUID id, UUID dono, PedidoEscrita pedido, String argumentosJson, String cliente,
            String hashArgumentos) {
        return banco.insertInto(OPERACAO)
                .set(OPERACAO.ID, id)
                .set(OPERACAO.DONO_ID, dono)
                .set(OPERACAO.CAPACIDADE, pedido.capacidade())
                .set(OPERACAO.ARGUMENTOS, JSONB.valueOf(argumentosJson))
                .set(OPERACAO.LOTE, pedido.lote())
                .set(OPERACAO.CLIENTE, cliente)
                .set(OPERACAO.CHAVE_IDEMPOTENCIA, pedido.chave() == null ? null : pedido.chave().valor())
                .set(OPERACAO.HASH_ARGUMENTOS, hashArgumentos)
                .onConflict(OPERACAO.DONO_ID, OPERACAO.CHAVE_IDEMPOTENCIA).doNothing()
                .execute() == 1;
    }

    @Override
    public Optional<OperacaoGravada> buscarPorChave(UUID dono, String chave) {
        return banco.select(OPERACAO.ID, OPERACAO.CAPACIDADE, OPERACAO.HASH_ARGUMENTOS)
                .from(OPERACAO)
                .where(OPERACAO.DONO_ID.eq(dono), OPERACAO.CHAVE_IDEMPOTENCIA.eq(chave))
                .fetchOptional(linha -> new OperacaoGravada(linha.value1(), linha.value2(), linha.value3()));
    }

    @Override
    public void inserirEfeito(UUID operacao, int ordem, TipoEfeito tipo, String entidade, UUID entidadeId,
            Long versaoAnterior, String detalhe) {
        banco.insertInto(EFEITO_OPERACAO)
                .set(EFEITO_OPERACAO.OPERACAO_ID, operacao)
                .set(EFEITO_OPERACAO.ORDEM, (short) ordem)
                .set(EFEITO_OPERACAO.TIPO, tipo.codigo())
                .set(EFEITO_OPERACAO.ENTIDADE, entidade)
                .set(EFEITO_OPERACAO.ENTIDADE_ID, entidadeId)
                .set(EFEITO_OPERACAO.VERSAO_ANTERIOR, versaoAnterior)
                .set(EFEITO_OPERACAO.DETALHE, detalhe)
                .execute();
    }

    @Override
    public void marcarDesfeita(UUID operacao, UUID desfeitaPor) {
        banco.update(OPERACAO).set(OPERACAO.ESTADO, "desfeita").set(OPERACAO.DESFEITA_POR, desfeitaPor)
                .where(OPERACAO.ID.eq(operacao)).execute();
    }

    @Override
    public void marcarAplicada(UUID operacao) {
        banco.update(OPERACAO).set(OPERACAO.ESTADO, "aplicada").setNull(OPERACAO.DESFEITA_POR)
                .where(OPERACAO.ID.eq(operacao)).execute();
    }

    @Override
    public void inserirVersao(EntidadeVersionada entidade, UUID entidadeId, long versao, String dadosJson,
            UUID operacao) {
        JSONB dados = JSONB.valueOf(dadosJson);
        var insercao = switch (entidade) {
            case INSTITUICAO -> banco.insertInto(INSTITUICAO_VERSAO,
                    INSTITUICAO_VERSAO.INSTITUICAO_ID, INSTITUICAO_VERSAO.VERSAO, INSTITUICAO_VERSAO.DADOS,
                    INSTITUICAO_VERSAO.OPERACAO_ID);
            case CONTA -> banco.insertInto(CONTA_VERSAO,
                    CONTA_VERSAO.CONTA_ID, CONTA_VERSAO.VERSAO, CONTA_VERSAO.DADOS, CONTA_VERSAO.OPERACAO_ID);
            case CARTAO_CREDITO -> banco.insertInto(CARTAO_CREDITO_VERSAO, CARTAO_CREDITO_VERSAO.CARTAO_CREDITO_ID,
                    CARTAO_CREDITO_VERSAO.VERSAO, CARTAO_CREDITO_VERSAO.DADOS, CARTAO_CREDITO_VERSAO.OPERACAO_ID);
            case AMBITO -> banco.insertInto(AMBITO_VERSAO,
                    AMBITO_VERSAO.AMBITO_ID, AMBITO_VERSAO.VERSAO, AMBITO_VERSAO.DADOS, AMBITO_VERSAO.OPERACAO_ID);
            case MEIO_PAGAMENTO -> banco.insertInto(MEIO_PAGAMENTO_VERSAO,
                    MEIO_PAGAMENTO_VERSAO.MEIO_PAGAMENTO_ID, MEIO_PAGAMENTO_VERSAO.VERSAO, MEIO_PAGAMENTO_VERSAO.DADOS,
                    MEIO_PAGAMENTO_VERSAO.OPERACAO_ID);
            case COMPROMISSO -> banco.insertInto(COMPROMISSO_VERSAO, COMPROMISSO_VERSAO.COMPROMISSO_ID, COMPROMISSO_VERSAO.VERSAO,
                    COMPROMISSO_VERSAO.DADOS, COMPROMISSO_VERSAO.OPERACAO_ID);
            case META -> banco.insertInto(META_VERSAO, META_VERSAO.META_ID, META_VERSAO.VERSAO, META_VERSAO.DADOS, META_VERSAO.OPERACAO_ID);
        };
        insercao.values(entidadeId, versao, dados, operacao).execute();
    }

    @Override
    public void inserirResultado(UUID operacao, String resultadoJson) {
        banco.insertInto(RESULTADO_OPERACAO)
                .set(RESULTADO_OPERACAO.OPERACAO_ID, operacao)
                .set(RESULTADO_OPERACAO.RESULTADO, JSONB.valueOf(resultadoJson))
                .execute();
    }

    @Override
    public Optional<String> resultado(UUID operacao) {
        return banco.select(RESULTADO_OPERACAO.RESULTADO).from(RESULTADO_OPERACAO)
                .where(RESULTADO_OPERACAO.OPERACAO_ID.eq(operacao))
                .fetchOptional(linha -> linha.value1().data());
    }

    @Override
    public ReferenciaCurta reservarReferencia(UUID dono, TipoReferencia tipo, UUID entidadeId) {
        for (int i = 0; i < TENTATIVAS_REFERENCIA; i++) {
            Optional<String> existente = banco.select(REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                    .where(REFERENCIA_CURTA.TIPO_ENTIDADE.eq(tipo.entidade()), REFERENCIA_CURTA.ENTIDADE_ID.eq(entidadeId))
                    .fetchOptional(REFERENCIA_CURTA.CODIGO);
            if (existente.isPresent()) {
                return ler(existente.get());
            }
            ReferenciaCurta nova = tipo.gerar(aleatorio);
            // Sem exceção na colisão: uma exceção abortaria a transação inteira no Postgres.
            int gravadas = banco.insertInto(REFERENCIA_CURTA)
                    .set(REFERENCIA_CURTA.CODIGO, nova.id())
                    .set(REFERENCIA_CURTA.DONO_ID, dono)
                    .set(REFERENCIA_CURTA.TIPO_ENTIDADE, tipo.entidade())
                    .set(REFERENCIA_CURTA.ENTIDADE_ID, entidadeId)
                    .onConflictDoNothing()
                    .execute();
            if (gravadas == 1) {
                return nova;
            }
        }
        throw new IllegalStateException("Sem código curto livre em " + TENTATIVAS_REFERENCIA + " tentativas");
    }

    private static ReferenciaCurta ler(String codigo) {
        return ReferenciaCurta.ler(codigo, TipoReferencia.MODULO)
                .orElseThrow(() -> new IllegalStateException("Código curto inválido no banco: " + codigo));
    }
}
