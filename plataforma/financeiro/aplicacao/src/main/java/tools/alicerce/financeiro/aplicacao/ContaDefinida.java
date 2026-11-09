package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code definir_conta}.
 *
 * @param cartao ausente se a conta não é cartão
 * @param marco desde quando o sistema responde pelo saldo; ausente em categorias e fontes de renda
 */
public record ContaDefinida(String id, String url, String nome, String tipo, String classe, List<String> aliases,
        String contaPai, boolean arquivada, String saldo, String moeda, String lancamentoAbertura, CartaoDefinido cartao, String marco) {

    /** @param meioPagamento o meio de crédito ligado ao cartão */
    public record CartaoDefinido(int diaFechamento, int diaVencimento, String limite, String limiteDisponivel, String meioPagamento) {}
}
