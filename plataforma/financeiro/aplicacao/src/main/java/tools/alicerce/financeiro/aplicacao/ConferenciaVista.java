package tools.alicerce.financeiro.aplicacao;

/**
 * A situação de conferência de uma conta, nas saídas de contexto e de cálculo.
 *
 * @param id o id curto da conta
 * @param ultimaConferencia a data da última conferência; ausente se nunca foi conferida
 * @param situacao a da última conferência: {@code conferida}, {@code pendente} ou {@code ajustada}; ausente se nunca foi conferida
 * @param diferencaPendente só com situação pendente: informado menos calculado, na moeda da conta
 * @param valeAte o último dia em que a conferência vale: 30 dias depois dela ou, sem nenhuma, do marco
 * @param vencida passou de {@code valeAte}
 */
public record ConferenciaVista(String id, String url, String nome, String moeda, String ultimaConferencia, String situacao,
        String diferencaPendente, String valeAte, boolean vencida) {}
