package tools.alicerce.financeiro.aplicacao;

/** O saldo de uma conta depois de uma escrita, na moeda da própria conta. */
public record SaldoConta(String conta, String saldo, String moeda) {}
