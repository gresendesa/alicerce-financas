package tools.alicerce.financeiro.aplicacao;

/** Resultado de {@code resolver_ocorrencia}: a ocorrência como ficou e o saldo do compromisso. */
public record OcorrenciaResolvida(String compromisso, String url, String nome, String acao, OcorrenciaVista ocorrencia,
        CompromissoVisto.SaldoVisto saldo) {}
