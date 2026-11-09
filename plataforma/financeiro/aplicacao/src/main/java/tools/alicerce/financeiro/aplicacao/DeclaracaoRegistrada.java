package tools.alicerce.financeiro.aplicacao;

/**
 * Resultado de {@code declarar}.
 *
 * @param anterior a declaração do mesmo tipo que esta substituiu; ausente se não havia
 */
public record DeclaracaoRegistrada(DeclaracaoVista declaracao, DeclaracaoVista anterior, String proximoPasso) {}
