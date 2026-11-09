package tools.alicerce.financeiro.persistencia;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.AMBITO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.CONTA;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.INSTITUICAO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.MEIO_PAGAMENTO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.REFERENCIA_CURTA;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.alicerce.financeiro.aplicacao.OperacaoEmCurso;
import tools.alicerce.financeiro.aplicacao.RepositorioEstrutura;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.ClasseConta;
import tools.alicerce.financeiro.dominio.Conta;
import tools.alicerce.financeiro.dominio.MeioPagamento;
import tools.alicerce.financeiro.dominio.TipoConta;
import tools.alicerce.financeiro.dominio.TipoMeioPagamento;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/** Persistência da estrutura. Toda escrita recebe a operação em curso. */
@Repository
class RepositorioEstruturaJooq implements RepositorioEstrutura {

    private final DSLContext banco;

    RepositorioEstruturaJooq(DSLContext banco) {
        this.banco = banco;
    }

    @Override
    public List<Conta> contas(UUID dono) {
        return banco.selectFrom(CONTA).where(CONTA.DONO_ID.eq(dono)).orderBy(CONTA.NOME).fetch(r -> new Conta(
                r.getId(), ClasseConta.valueOf(r.getClasse().name().toUpperCase(Locale.ROOT)),
                TipoConta.valueOf(r.getTipo().name().toUpperCase(Locale.ROOT)), r.getNome(), Arrays.asList(r.getAliases()),
                r.getContaPaiId(), r.getInstituicaoId(), r.getMoeda(), r.getArquivadoEm() != null, r.getVersao()));
    }

    @Override
    public List<Ambito> ambitos(UUID dono) {
        return banco.selectFrom(AMBITO).where(AMBITO.DONO_ID.eq(dono)).orderBy(AMBITO.NOME).fetch(r -> new Ambito(
                r.getId(), r.getNome(), Arrays.asList(r.getAliases()), r.getPadrao(), r.getArquivadoEm() != null, r.getVersao()));
    }

    @Override
    public List<MeioPagamento> meios(UUID dono) {
        return banco.selectFrom(MEIO_PAGAMENTO).where(MEIO_PAGAMENTO.DONO_ID.eq(dono)).orderBy(MEIO_PAGAMENTO.NOME)
                .fetch(r -> new MeioPagamento(r.getId(), TipoMeioPagamento.valueOf(r.getTipo().name().toUpperCase(Locale.ROOT)),
                        r.getNome(), Arrays.asList(r.getAliases()), r.getContaLiquidacaoId(), r.getPadrao(),
                        r.getAmbitoPadraoId(), r.getArquivadoEm() != null, r.getVersao()));
    }

    @Override
    public Optional<UUID> entidadeDaReferencia(UUID dono, String codigo) {
        return banco.select(REFERENCIA_CURTA.ENTIDADE_ID).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.CODIGO.eq(codigo), REFERENCIA_CURTA.DONO_ID.eq(dono))
                .fetchOptional(REFERENCIA_CURTA.ENTIDADE_ID);
    }

    @Override
    public Optional<String> referenciaDe(UUID dono, TipoReferencia tipo, UUID entidade) {
        return banco.select(REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.DONO_ID.eq(dono), REFERENCIA_CURTA.TIPO_ENTIDADE.eq(tipo.entidade()),
                        REFERENCIA_CURTA.ENTIDADE_ID.eq(entidade))
                .fetchOptional(REFERENCIA_CURTA.CODIGO);
    }

    @Override
    public Map<UUID, String> referenciasDeConta(UUID dono) {
        return banco.select(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.DONO_ID.eq(dono), REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.CONTA.entidade()))
                .stream().collect(Collectors.toMap(r -> r.value1(), r -> r.value2()));
    }

    @Override
    public Map<UUID, String> referenciasDeFatura(UUID dono) {
        return banco.select(REFERENCIA_CURTA.ENTIDADE_ID, REFERENCIA_CURTA.CODIGO).from(REFERENCIA_CURTA)
                .where(REFERENCIA_CURTA.DONO_ID.eq(dono), REFERENCIA_CURTA.TIPO_ENTIDADE.eq(TipoReferencia.FATURA.entidade()))
                .stream().collect(Collectors.toMap(r -> r.value1(), r -> r.value2()));
    }

    @Override
    public Optional<UUID> instituicao(UUID dono, String nome) {
        return banco.select(INSTITUICAO.ID).from(INSTITUICAO)
                .where(INSTITUICAO.DONO_ID.eq(dono), INSTITUICAO.NOME.eq(nome))
                .fetchOptional(INSTITUICAO.ID);
    }

    @Override
    public void inserirInstituicao(OperacaoEmCurso operacao, UUID id, String nome) {
        banco.insertInto(INSTITUICAO).set(INSTITUICAO.ID, id).set(INSTITUICAO.DONO_ID, operacao.dono())
                .set(INSTITUICAO.NOME, nome).execute();
    }

    @Override
    public void inserirConta(OperacaoEmCurso operacao, Conta c, LocalDate marco) {
        banco.insertInto(CONTA).set(CONTA.ID, c.id()).set(CONTA.DONO_ID, operacao.dono())
                .set(CONTA.CLASSE, tools.alicerce.financeiro.persistencia.jooq.enums.ClasseConta.valueOf(c.classe().name().toLowerCase(Locale.ROOT)))
                .set(CONTA.TIPO, tools.alicerce.financeiro.persistencia.jooq.enums.TipoConta.valueOf(c.tipo().codigo()))
                .set(CONTA.NOME, c.nome()).set(CONTA.ALIASES, c.aliases().toArray(String[]::new))
                .set(CONTA.CONTA_PAI_ID, c.contaPai()).set(CONTA.INSTITUICAO_ID, c.instituicao())
                .set(CONTA.MOEDA, c.moeda()).set(CONTA.VERSAO, c.versao()).set(CONTA.MARCO_INICIAL, marco)
                .execute();
    }

    @Override
    public Optional<LocalDate> marco(UUID conta) {
        return banco.select(CONTA.MARCO_INICIAL).from(CONTA).where(CONTA.ID.eq(conta)).fetchOptional(CONTA.MARCO_INICIAL);
    }

    @Override
    public Map<UUID, LocalDate> marcos(UUID dono) {
        return banco.select(CONTA.ID, CONTA.MARCO_INICIAL).from(CONTA).where(CONTA.DONO_ID.eq(dono), CONTA.MARCO_INICIAL.isNotNull())
                .stream().collect(Collectors.toMap(r -> r.value1(), r -> r.value2()));
    }

    @Override
    public void atualizarConta(OperacaoEmCurso operacao, Conta c) {
        banco.update(CONTA).set(CONTA.NOME, c.nome()).set(CONTA.ALIASES, c.aliases().toArray(String[]::new))
                .set(CONTA.CONTA_PAI_ID, c.contaPai()).set(CONTA.INSTITUICAO_ID, c.instituicao()).set(CONTA.MOEDA, c.moeda())
                .set(CONTA.ARQUIVADO_EM, arquivadoEm(CONTA.ARQUIVADO_EM, c.arquivada())).set(CONTA.VERSAO, c.versao())
                .where(CONTA.ID.eq(c.id()), CONTA.DONO_ID.eq(operacao.dono()))
                .execute();
    }

    @Override
    public void inserirAmbito(OperacaoEmCurso operacao, Ambito a) {
        banco.insertInto(AMBITO).set(AMBITO.ID, a.id()).set(AMBITO.DONO_ID, operacao.dono()).set(AMBITO.NOME, a.nome())
                .set(AMBITO.ALIASES, a.aliases().toArray(String[]::new)).set(AMBITO.PADRAO, a.padrao())
                .set(AMBITO.VERSAO, a.versao())
                .execute();
    }

    @Override
    public void atualizarAmbito(OperacaoEmCurso operacao, Ambito a) {
        banco.update(AMBITO).set(AMBITO.NOME, a.nome()).set(AMBITO.ALIASES, a.aliases().toArray(String[]::new))
                .set(AMBITO.PADRAO, a.padrao()).set(AMBITO.ARQUIVADO_EM, arquivadoEm(AMBITO.ARQUIVADO_EM, a.arquivado()))
                .set(AMBITO.VERSAO, a.versao())
                .where(AMBITO.ID.eq(a.id()), AMBITO.DONO_ID.eq(operacao.dono()))
                .execute();
    }

    @Override
    public void inserirMeio(OperacaoEmCurso operacao, MeioPagamento m) {
        banco.insertInto(MEIO_PAGAMENTO).set(MEIO_PAGAMENTO.ID, m.id()).set(MEIO_PAGAMENTO.DONO_ID, operacao.dono())
                .set(MEIO_PAGAMENTO.TIPO, tools.alicerce.financeiro.persistencia.jooq.enums.TipoMeioPagamento.valueOf(m.tipo().codigo()))
                .set(MEIO_PAGAMENTO.NOME, m.nome()).set(MEIO_PAGAMENTO.ALIASES, m.aliases().toArray(String[]::new))
                .set(MEIO_PAGAMENTO.CONTA_LIQUIDACAO_ID, m.contaLiquidacao()).set(MEIO_PAGAMENTO.PADRAO, m.padrao())
                .set(MEIO_PAGAMENTO.AMBITO_PADRAO_ID, m.ambitoPadrao()).set(MEIO_PAGAMENTO.VERSAO, m.versao())
                .execute();
    }

    @Override
    public void atualizarMeio(OperacaoEmCurso operacao, MeioPagamento m) {
        banco.update(MEIO_PAGAMENTO)
                .set(MEIO_PAGAMENTO.TIPO, tools.alicerce.financeiro.persistencia.jooq.enums.TipoMeioPagamento.valueOf(m.tipo().codigo()))
                .set(MEIO_PAGAMENTO.NOME, m.nome()).set(MEIO_PAGAMENTO.ALIASES, m.aliases().toArray(String[]::new))
                .set(MEIO_PAGAMENTO.CONTA_LIQUIDACAO_ID, m.contaLiquidacao()).set(MEIO_PAGAMENTO.PADRAO, m.padrao())
                .set(MEIO_PAGAMENTO.AMBITO_PADRAO_ID, m.ambitoPadrao())
                .set(MEIO_PAGAMENTO.ARQUIVADO_EM, arquivadoEm(MEIO_PAGAMENTO.ARQUIVADO_EM, m.arquivado()))
                .set(MEIO_PAGAMENTO.VERSAO, m.versao())
                .where(MEIO_PAGAMENTO.ID.eq(m.id()), MEIO_PAGAMENTO.DONO_ID.eq(operacao.dono()))
                .execute();
    }

    /** Mantém a data do arquivamento original; desarquivar limpa. */
    private static Field<OffsetDateTime> arquivadoEm(Field<OffsetDateTime> campo, boolean arquivado) {
        return arquivado ? DSL.coalesce(campo, DSL.currentOffsetDateTime()) : DSL.castNull(campo);
    }
}
