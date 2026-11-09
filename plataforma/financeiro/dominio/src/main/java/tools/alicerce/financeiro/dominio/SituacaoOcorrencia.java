package tools.alicerce.financeiro.dominio;

import java.util.Locale;

/** A situação de uma ocorrência de compromisso, sempre derivada da regra e do que foi gravado. */
public enum SituacaoOcorrencia {
    /** Quitada pelos pagamentos vigentes vinculados a ela, um ou mais. */
    PAGA,
    /** Uma das primeiras parcelas, paga antes de o compromisso entrar no sistema. */
    PAGA_ANTES,
    /** O dono disse que esta não vence: não é devida nem esperada. */
    PULADA,
    /** Em aberto, inteira ou pelo que falta, com o vencimento já passado. */
    ATRASADA,
    /** Em aberto, inteira ou pelo que falta, vencendo hoje ou depois. */
    PREVISTA;

    public boolean emAberto() {
        return this == ATRASADA || this == PREVISTA;
    }

    public boolean paga() {
        return this == PAGA || this == PAGA_ANTES;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }
}
