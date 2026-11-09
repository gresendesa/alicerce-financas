package tools.alicerce.financeiro.aplicacao;

/** Uma operação nas saídas: o que o dono escolhe para desfazer. */
public record OperacaoVista(String id, String url, String capacidade, String resumo, String lote, String cliente,
        String horario, String estado) {}
