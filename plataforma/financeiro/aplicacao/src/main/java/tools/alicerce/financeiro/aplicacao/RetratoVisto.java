package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Um retrato aberto por {@code resolver_referencia}: {@code parametros} e {@code resultado} são o JSON
 * guardado, exatamente o que o assistente viu.
 *
 * @param comparacao a diferença para o cálculo de agora, só com {@code comparar_agora}
 */
public record RetratoVisto(String id, String url, String capacidade, String dataReferencia, JsonNode parametros,
        JsonNode resultado, Comparacao comparacao) {

    /**
     * @param mudou algum total ou linha de quebra está diferente
     * @param linhasAlteradas as linhas de quebra que mudaram
     * @param totalConvertido o total dos saldos convertidos; ausente se o retrato não tem conversão
     * @param acumulado num ritmo, o gasto do mês até o dia do retrato; no resumo, ausente
     * @param tipico num ritmo, o típico até o mesmo dia; ausente no resumo ou sem típico
     * @param saldoProjetado nos cortes, o saldo projetado no fim do período; ausente nos outros
     * @param distancia nos cortes, a distância até o alvo; ausente nos outros
     * @param saldosFinais na projeção, o saldo final de cada mês da linha de base, nas duas estimativas; ausente nos outros
     */
    public record Comparacao(String calculadoEm, boolean mudou, Diferenca entradas, Diferenca saidas, Diferenca saldo,
            List<LinhaAlterada> linhasAlteradas, Diferenca totalConvertido, Diferenca acumulado, Diferenca tipico, Diferenca saldoProjetado,
            Diferenca distancia, List<SaldoFinal> saldosFinais) {}

    /** O saldo final de um mês da linha de base, no retrato e agora, em cada estimativa. */
    public record SaldoFinal(String mes, Diferenca p50, Diferenca p80) {}

    public record Diferenca(String retrato, String agora, String diferenca) {}

    public record LinhaAlterada(String agruparPor, String nome, Diferenca entradas, Diferenca saidas) {}

    RetratoVisto comComparacao(Comparacao nova) {
        return new RetratoVisto(id, url, capacidade, dataReferencia, parametros, resultado, nova);
    }
}
