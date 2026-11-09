package tools.alicerce.financeiro.aplicacao;

/**
 * Os parâmetros de uma prestação de contas já resolvidos, como o retrato os guarda.
 *
 * @param ambito o nome do âmbito quando o retrato foi gravado
 * @param validadeDias por quantos dias o link abre a partir da criação
 */
public record ParametrosPrestacao(String de, String ate, String ambitoId, String ambito, boolean incluirComprovantes, int validadeDias) {

    /** O mesmo recorte em {@code resumo_periodo}: por categoria, em competência, sem comparação. */
    ParametrosResumo resumo() {
        return new ParametrosResumo(de, ate, java.util.List.of("categoria"), ambitoId, ambito, null, CalculoDeResumo.COMPETENCIA, false);
    }
}
