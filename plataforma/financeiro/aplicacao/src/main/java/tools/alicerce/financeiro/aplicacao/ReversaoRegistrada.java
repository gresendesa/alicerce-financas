package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * O que o {@code desfazer} mostra na prévia e devolve ao aplicar.
 *
 * @param operacoes as operações revertidas, da última para a primeira
 * @param estruturaMantida contas técnicas, âmbito automático e instituições, que outras operações podem
 *     usar e não são arquivados
 * @param efeitosExternos o que muda fora do Alicerce: um link de prestação de contas que deixa de abrir, ou volta
 * @param confirmacao o token, só na prévia
 * @param confirmacaoValidaAte até quando o token vale, só na prévia
 * @param proximoPasso como aplicar, só na prévia
 * @param declaracoesRevertidas as declarações do perfil cuja vigente muda: a que sai e a que volta a valer
 */
public record ReversaoRegistrada(List<OperacaoVista> operacoes, List<LancamentoRevertido> lancamentosEstornados,
        List<LancamentoRevertido> lancamentosRestabelecidos, List<EntidadeRevertida> versoesRestauradas,
        List<EntidadeRevertida> entidadesArquivadas, List<String> estruturaMantida, List<SaldoConta> saldos,
        List<String> efeitosExternos, String confirmacao, String confirmacaoValidaAte, String proximoPasso,
        List<DeclaracaoRevertida> declaracoesRevertidas) {

    /**
     * @param sai a declaração que deixa de valer
     * @param volta a que volta a valer; ausente se nenhuma do tipo vale depois
     */
    public record DeclaracaoRevertida(String tipo, DeclaracaoVista sai, DeclaracaoVista volta) {}

    public record LancamentoRevertido(String id, String url, String descricao, String valor, String moeda) {}

    public record EntidadeRevertida(String entidade, String nome) {}

    ReversaoRegistrada comConfirmacao(String token, String validaAte, String passo) {
        return new ReversaoRegistrada(operacoes, lancamentosEstornados, lancamentosRestabelecidos, versoesRestauradas,
                entidadesArquivadas, estruturaMantida, saldos, efeitosExternos, token, validaAte, passo, declaracoesRevertidas);
    }

    ReversaoRegistrada comDeclaracoes(List<DeclaracaoRevertida> revertidas) {
        return new ReversaoRegistrada(operacoes, lancamentosEstornados, lancamentosRestabelecidos, versoesRestauradas,
                entidadesArquivadas, estruturaMantida, saldos, efeitosExternos, confirmacao, confirmacaoValidaAte, proximoPasso, revertidas);
    }
}
