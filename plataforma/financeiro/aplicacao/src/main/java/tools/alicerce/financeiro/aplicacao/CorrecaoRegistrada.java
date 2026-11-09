package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code corrigir_lancamento}.
 *
 * @param estorno o id curto do estorno; {@code null} numa simulação
 * @param novo o id curto do lançamento certo; {@code null} ao anular e numa simulação
 * @param movimentos os movimentos do lançamento certo; vazio ao anular
 * @param original a moeda original do lançamento certo, se houver
 * @param faturas as faturas tocadas, já com o efeito da correção; vazio fora do cartão
 * @param procedencia a do lançamento certo; ausente ao anular
 */
public record CorrecaoRegistrada(String acao, String lancamento, String estorno, String urlEstorno, String novo,
        String urlNovo, List<LancamentoVisto.MovimentoVisto> movimentos, List<SaldoConta> saldos, OriginalVisto original,
        List<FaturaVista> faturas, String procedencia) {

    CorrecaoRegistrada simulado() {
        return new CorrecaoRegistrada(acao, lancamento, null, null, null, null, movimentos, saldos, original, faturas, procedencia);
    }
}
