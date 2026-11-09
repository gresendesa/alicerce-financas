package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code resumo_periodo}.
 *
 * @param retrato o id curto do retrato, que guarda este resultado para sempre
 * @param urlVistaViva o mesmo relatório, recalculado a cada abertura
 */
public record ResumoRegistrado(String retrato, String urlRetrato, String urlVistaViva, ResumoVisto resumo) {}
