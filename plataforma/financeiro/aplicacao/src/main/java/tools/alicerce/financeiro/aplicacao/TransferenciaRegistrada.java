package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code registrar_transferencia}.
 *
 * @param id o id curto; {@code null} numa simulação
 * @param tipo transferencia ou pagamento_fatura
 * @param valor o valor do lançamento; num câmbio, o lado na moeda do dono
 * @param fatura a fatura depois do pagamento; ausente em transferência simples
 * @param original num câmbio, o lado na outra moeda e a cotação guardada; nunca se soma a {@code valor}
 * @param meta num aporte ou resgate: a meta, já com o progresso depois dele
 */
public record TransferenciaRegistrada(String id, String url, String tipo, String valor, String moeda, String data, String descricao,
        String de, String ate, List<SaldoConta> saldos, FaturaVista fatura,
        OriginalVisto original, MetaMovimentada meta) {

    /**
     * A meta depois do aporte ou do resgate. Os números vêm do domínio.
     *
     * @param movimento {@code aporte} ou {@code resgate}
     * @param aporteMensalNecessario ausente sem data alvo
     */
    public record MetaMovimentada(String id, String url, String nome, String movimento, String progresso, String valorAlvo, String falta,
            String aporteMensalNecessario) {}

    TransferenciaRegistrada simulado() {
        return new TransferenciaRegistrada(null, null, tipo, valor, moeda, data, descricao, de, ate, saldos, fatura, original, meta);
    }
}
