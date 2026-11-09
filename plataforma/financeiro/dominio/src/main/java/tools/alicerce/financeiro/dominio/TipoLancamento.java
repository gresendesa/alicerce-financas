package tools.alicerce.financeiro.dominio;

/** Os tipos de lançamento usados até aqui; os demais entram com as capacidades que os criam. */
public enum TipoLancamento {
    GASTO,
    GANHO,
    TRANSFERENCIA,
    PAGAMENTO_FATURA,
    /** Transferência marcada com uma meta: aporte, ou resgate no sentido contrário. */
    APORTE_META,
    SALDO_ABERTURA,
    /** A diferença de uma conferência aceita pelo dono, contra Ajuste não identificado. */
    AJUSTE,
    ESTORNO
}
