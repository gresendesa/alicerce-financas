package tools.alicerce.convencoes;

/**
 * Códigos de erro que o assistente vê, conforme a tabela de nomenclatura da modelagem. O
 * {@code insufficient_scope} costuma ser resposta HTTP da segurança; só o {@code desfazer} o devolve
 * como resultado de tool, porque o escopo depende das operações revertidas, que o filtro não conhece.
 */
public enum CodigoErro {
    VALIDACAO_FALHOU,
    CAMPOS_FALTANDO,
    REFERENCIA_INEXISTENTE,
    REFERENCIA_AMBIGUA,
    POSSIVEL_DUPLICATA,
    NOTA_DUPLICADA,
    CONFLITO_IDEMPOTENCIA,
    VIOLACAO_INVARIANTE,
    NAO_ENCONTRADO,
    CONFLITO,
    MODULO_ERRADO,
    DESFAZER_BLOQUEADO,
    JA_DESFEITA,
    CONFIRMACAO_EXPIRADA,
    CONFIRMACAO_INVALIDA,
    INSUFFICIENT_SCOPE,
    ERRO_INTERNO;

    /** O código como o assistente o vê: {@code validacao_falhou}. */
    public String codigo() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Só o erro interno vale a pena repetir sem mudar nada. */
    public boolean podeRepetir() {
        return this == ERRO_INTERNO;
    }
}
