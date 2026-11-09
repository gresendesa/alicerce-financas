package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code cortes_necessarios}.
 *
 * @param retrato o id curto do retrato, que guarda este resultado para sempre
 */
public record CortesRegistrado(String retrato, String urlRetrato, CortesVisto cortes) {}
