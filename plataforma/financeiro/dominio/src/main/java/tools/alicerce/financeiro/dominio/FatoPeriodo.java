package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/**
 * Um movimento vigente de gasto ou ganho, com o que o resumo de período precisa do lançamento.
 *
 * @param contaComSaldo o movimento é o lado do dinheiro, e não o da categoria ou fonte de renda
 * @param valor o valor na moeda da conta, nunca o original
 * @param exogeno o lançamento veio de fora e não passou pelas contas
 */
public record FatoPeriodo(UUID lancamento, String codigo, TipoLancamento tipo, LocalDate data, String descricao,
        String contraparte, UUID meio, UUID conta, boolean contaComSaldo, UUID ambito, Dinheiro valor, boolean exogeno) {

    public FatoPeriodo(UUID lancamento, String codigo, TipoLancamento tipo, LocalDate data, String descricao, String contraparte,
            UUID meio, UUID conta, boolean contaComSaldo, UUID ambito, Dinheiro valor) {
        this(lancamento, codigo, tipo, data, descricao, contraparte, meio, conta, contaComSaldo, ambito, valor, false);
    }

    public boolean entrada() {
        return tipo == TipoLancamento.GANHO;
    }

    /** O valor sem sinal: o sinal só diz o lado do movimento, e quem separa entrada de saída é o tipo. */
    public Dinheiro absoluto() {
        return valor.ehNegativo() ? valor.negar() : valor;
    }
}
