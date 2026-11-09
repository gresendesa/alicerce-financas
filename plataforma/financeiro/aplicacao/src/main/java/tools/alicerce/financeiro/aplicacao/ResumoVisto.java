package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Um resumo de período como o assistente o lê e como o retrato o guarda. Entradas, saídas e quebras estão na
 * moeda do dono: o valor original de uma compra em outra moeda nunca entra aqui, e transferência não conta.
 *
 * @param dataReferencia quando foi calculado
 * @param avisoVisao como ler a visão pedida; ausente em competência. Retratos antigos guardam o aviso de quando as visões eram iguais
 * @param periodoAnterior ausente se a comparação não foi pedida
 * @param historico a parte de {@code entradas} e {@code saidas} que veio de fora; ausente se não há
 * @param avisoHistorico o período começa antes do marco de alguma conta, e por isso pode estar incompleto
 * @param saldos os saldos no fim do período, convertidos; ausente se o dono só tem contas na própria moeda
 * @param ajustes as diferenças de conferência aceitas no período, fora de entradas e saídas; ausente se não há
 * @param conferenciasVencidas as contas sem conferência há mais de 30 dias, quando o cálculo foi feito
 */
public record ResumoVisto(String dataReferencia, String de, String ate, String visao, String avisoVisao, String moeda,
        String ambito, String entradas, String saidas, String saldo, int quantidadeLancamentos, List<QuebraVista> quebras,
        List<DestaqueVisto> maioresEntradas, List<DestaqueVisto> maioresSaidas, PeriodoAnterior periodoAnterior,
        HistoricoVisto historico, String avisoHistorico, SaldosVistos saldos, AjustesVistos ajustes,
        List<ConferenciaVista> conferenciasVencidas) {

    /**
     * Linha própria das diferenças de conferência aceitas (Ajuste não identificado), para elas não sumirem.
     *
     * @param valor a soma na moeda do dono: positivo, apareceu dinheiro que não estava registrado
     */
    public record AjustesVistos(String valor, int quantidadeLancamentos, List<DestaqueVisto> lancamentos, String nota) {}

    /**
     * Os saldos no fim do período, das contas e dos cartões, com dívida negativa.
     *
     * @param data até quando os saldos e as cotações valem
     * @param moeda a moeda dos totais: a do dono
     * @param saldoMoedaDono a soma das contas que já estão nela
     * @param saldoConvertido a soma das contas estrangeiras com cotação, convertidas
     * @param total as duas somas juntas; o que está sem cotação fica fora
     * @param contas as contas em outra moeda
     * @param avisoSemCotacao o que ficou fora do total; ausente se nada ficou
     */
    public record SaldosVistos(String data, String moeda, String saldoMoedaDono, String saldoConvertido, String total,
            List<ContaConvertida> contas, String nota, String avisoSemCotacao) {}

    /**
     * @param moeda a moeda de {@code saldo}
     * @param cotacao quanto vale uma unidade da moeda da conta na moeda do dono; ausente sem cotação
     * @param lancamentoCotacao o id curto do lançamento em que o dono registrou a cotação
     * @param saldoConvertido ausente sem cotação
     * @param moedaSaldoConvertido a moeda de {@code saldoConvertido}: a do dono
     */
    public record ContaConvertida(String id, String nome, String moeda, String saldo, String cotacao, String dataCotacao,
            String lancamentoCotacao, String saldoConvertido, String moedaSaldoConvertido) {}

    /** Já está dentro dos totais e nunca se soma a eles. @param nota como ler esses valores */
    public record HistoricoVisto(String entradasExogenas, String saidasExogenas, int quantidadeLancamentos, String nota) {}

    public record QuebraVista(String agruparPor, List<LinhaVista> linhas) {}

    /**
     * @param id o id curto da conta, ou o id do meio ou do âmbito; ausente em contraparte
     * @param participacaoEntradas percentual do total de entradas
     */
    public record LinhaVista(String id, String nome, String entradas, String saidas, String participacaoEntradas,
            String participacaoSaidas) {}

    /** @param procedencia {@code exogeno} se veio de fora e não passou pelas contas */
    public record DestaqueVisto(String id, String url, String data, String descricao, String valor, String procedencia) {}

    /** @param variacaoEntradas diferença para o período anterior; o percentual fica ausente se a base é zero */
    public record PeriodoAnterior(String de, String ate, String entradas, String saidas, String saldo, String variacaoEntradas,
            String variacaoSaidas, String variacaoSaldo, String variacaoEntradasPercentual, String variacaoSaidasPercentual,
            String variacaoSaldoPercentual) {}
}
