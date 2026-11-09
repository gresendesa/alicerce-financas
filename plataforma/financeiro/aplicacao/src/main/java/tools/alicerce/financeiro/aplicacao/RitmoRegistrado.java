package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code ritmo_gastos}.
 *
 * @param retrato o id curto do retrato, que guarda este resultado para sempre
 */
public record RitmoRegistrado(String retrato, String urlRetrato, RitmoVisto ritmo) {}
