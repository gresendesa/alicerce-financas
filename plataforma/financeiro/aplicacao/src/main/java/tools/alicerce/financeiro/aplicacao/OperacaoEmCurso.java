package tools.alicerce.financeiro.aplicacao;

import java.util.Objects;
import java.util.UUID;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.jackson.databind.json.JsonMapper;

/**
 * A operação aberta pelo registrador durante uma escrita. Só o registrador a cria, e todo método de
 * persistência que escreve no banco a recebe como parâmetro (o teste de arquitetura confere): assim,
 * nenhuma escrita acontece fora de uma operação. Cada efeito declarado aqui vira uma linha de
 * {@code efeito_operacao}, na ordem, e as entidades mutáveis ganham a versão correspondente.
 */
public final class OperacaoEmCurso {

    private final UUID id;
    private final UUID dono;
    private final ReferenciaCurta referencia;
    private final RepositorioOperacoes operacoes;
    private final JsonMapper json;
    private int efeitos;
    private boolean encerrada;

    OperacaoEmCurso(UUID id, UUID dono, ReferenciaCurta referencia, RepositorioOperacoes operacoes, JsonMapper json) {
        this.id = Objects.requireNonNull(id, "id");
        this.dono = Objects.requireNonNull(dono, "dono");
        this.referencia = Objects.requireNonNull(referencia, "referencia");
        this.operacoes = operacoes;
        this.json = json;
    }

    public UUID id() {
        return id;
    }

    public UUID dono() {
        return dono;
    }

    public ReferenciaCurta referencia() {
        return referencia;
    }

    public void lancamentoCriado(UUID lancamento) {
        efeito(TipoEfeito.LANCAMENTO_CRIADO, "lancamento", lancamento, null, null);
    }

    /** Um lançamento foi estornado: {@code estorno} é a linha de estorno, que aponta para ele. */
    public void lancamentoEstornado(UUID estorno) {
        efeito(TipoEfeito.LANCAMENTO_ESTORNADO, "lancamento", estorno, null, null);
    }

    /** O ponteiro da nota saiu de {@code anterior}; o desfazer o devolve para lá. */
    public void chaveFiscalMovida(String chave, UUID anterior) {
        efeito(TipoEfeito.CHAVE_FISCAL_MOVIDA, "lancamento", anterior, null, chave);
    }

    /** O comprovante saiu de {@code de} e foi para {@code para}; nulo é nenhum lançamento. */
    public void comprovanteVinculado(UUID comprovante, UUID de, UUID para) {
        efeito(TipoEfeito.COMPROVANTE_VINCULADO, "comprovante", comprovante, null, new VinculoComprovante(de, para).texto());
    }

    /** Um link de envio nasceu. O token cru nunca passa por aqui. */
    public void tokenAnexoEmitido(UUID token) {
        efeito(TipoEfeito.TOKEN_ANEXO_EMITIDO, "token_anexo", token, null, null);
    }

    /** Um cálculo ficou guardado num retrato. */
    public void retratoGravado(UUID retrato) {
        efeito(TipoEfeito.RETRATO_GRAVADO, "retrato", retrato, null, null);
    }

    /** Uma fatura nasceu sob demanda. */
    public void faturaCriada(UUID fatura) {
        efeito(TipoEfeito.FATURA_CRIADA, "fatura", fatura, null, null);
    }

    /** Uma conferência foi gravada. Sem inverso próprio: a leitura ignora a de operação desfeita. */
    public void conferenciaRegistrada(UUID conferencia) {
        efeito(TipoEfeito.CONFERENCIA_REGISTRADA, "conferencia", conferencia, null, null);
    }

    /** Uma ocorrência foi paga, pulada ou adiada. Sem inverso próprio: a leitura ignora a de operação desfeita. */
    public void ocorrenciaRegistrada(UUID ocorrencia) {
        efeito(TipoEfeito.OCORRENCIA_REGISTRADA, "ocorrencia_compromisso", ocorrencia, null, null);
    }

    /** Uma declaração do perfil foi gravada. Sem inverso próprio: a leitura ignora a de operação desfeita, e a anterior volta a valer. */
    public void declaracaoRegistrada(UUID declaracao) {
        efeito(TipoEfeito.DECLARACAO_REGISTRADA, "declaracao", declaracao, null, null);
    }

    /** Um link de prestação de contas nasceu. O token cru nunca passa por aqui. Sem inverso: desfeita a operação, o link não abre. */
    public void linkCriado(UUID link) {
        efeito(TipoEfeito.LINK_CRIADO, "link_compartilhamento", link, null, null);
    }

    /** Um link de prestação de contas foi revogado. Sem inverso: desfeita a operação, o link volta a abrir até vencer. */
    public void linkRevogado(UUID link) {
        efeito(TipoEfeito.LINK_REVOGADO, "link_compartilhamento", link, null, null);
    }

    /** Esta operação desfez {@code alvo}. */
    void operacaoDesfeita(UUID alvo) {
        efeito(TipoEfeito.OPERACAO_DESFEITA, "operacao", alvo, null, null);
        operacoes.marcarDesfeita(alvo, id);
    }

    /** Esta operação devolveu {@code alvo} ao estado aplicada. */
    void operacaoRestabelecida(UUID alvo) {
        efeito(TipoEfeito.OPERACAO_RESTABELECIDA, "operacao", alvo, null, null);
        operacoes.marcarAplicada(alvo);
    }

    /** Uma entidade mutável nasceu, na versão 1, com este estado completo. */
    public void entidadeCriada(EntidadeVersionada entidade, UUID entidadeId, Object dados) {
        efeito(TipoEfeito.ENTIDADE_CRIADA, entidade.entidade(), entidadeId, null, null);
        operacoes.inserirVersao(entidade, entidadeId, 1, json.writeValueAsString(dados), id);
    }

    /** Uma entidade mutável passou de {@code versaoAnterior} para a seguinte, com este estado completo. */
    public void entidadeAlterada(EntidadeVersionada entidade, UUID entidadeId, long versaoAnterior, Object dados) {
        efeito(TipoEfeito.ENTIDADE_ALTERADA, entidade.entidade(), entidadeId, versaoAnterior, null);
        operacoes.inserirVersao(entidade, entidadeId, versaoAnterior + 1, json.writeValueAsString(dados), id);
    }

    /** O endereço curto da entidade, criado agora se ainda não existir. */
    public ReferenciaCurta referenciar(TipoReferencia tipo, UUID entidadeId) {
        aberta();
        return operacoes.reservarReferencia(dono, tipo, entidadeId);
    }

    int efeitos() {
        return efeitos;
    }

    void encerrar() {
        encerrada = true;
    }

    private void efeito(TipoEfeito tipo, String entidade, UUID entidadeId, Long versaoAnterior,
            String detalhe) {
        aberta();
        efeitos++;
        operacoes.inserirEfeito(id, efeitos, tipo, entidade, Objects.requireNonNull(entidadeId, "entidadeId"), versaoAnterior,
                detalhe);
    }

    private void aberta() {
        if (encerrada) {
            throw new IllegalStateException("Operação " + referencia.id() + " já encerrada");
        }
    }
}
