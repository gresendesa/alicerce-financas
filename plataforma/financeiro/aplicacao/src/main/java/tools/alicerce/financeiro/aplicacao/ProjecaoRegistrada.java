package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code projetar}.
 *
 * @param retrato o id curto do retrato, que guarda este resultado para sempre
 */
public record ProjecaoRegistrada(String retrato, String urlRetrato, ProjecaoVista projecao) {}
