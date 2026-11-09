package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code conferir_saldo}. Todo número sai do domínio, na moeda da conta.
 *
 * @param fatura o id curto da fatura conferida; ausente em conferência de conta
 * @param diferenca informado menos calculado: positivo, há mais dinheiro (ou mais dívida, numa fatura) do que o registrado
 * @param situacao {@code conferida}, {@code pendente} ou {@code ajustada}
 * @param ajuste o lançamento de ajuste contra Ajuste não identificado; ausente se não houve
 * @param anterior a conferência anterior desta conta ou fatura; ausente se é a primeira
 * @param ocorrenciasCandidatas ocorrências de compromisso esperadas e não registradas desde a conferência anterior
 * @param comprasCandidatas numa fatura com diferença, os itens dela, os de valor igual à diferença primeiro
 * @param valeAte até quando esta conferência vale
 */
public record ConferenciaRegistrada(String conta, String contaId, String fatura, String urlFatura, String data, String saldoInformado,
        String saldoCalculado, String diferenca, String moeda, String situacao, LancamentoAjuste ajuste, Anterior anterior,
        List<ItemAgenda> ocorrenciasCandidatas, List<CompraCandidata> comprasCandidatas, String valeAte, String proximoPasso) {

    public record LancamentoAjuste(String id, String url, String valor, String contaTecnica) {}

    public record Anterior(String data, String situacao, String diferenca) {}

    /** @param igualADiferenca o valor do item é igual à diferença, em módulo */
    public record CompraCandidata(String lancamento, String url, String data, String descricao, String parcela, String valorNaFatura,
            boolean igualADiferenca) {}

    ConferenciaRegistrada simulada() {
        return new ConferenciaRegistrada(conta, contaId, fatura, urlFatura, data, saldoInformado, saldoCalculado, diferenca, moeda, situacao,
                ajuste == null ? null : new LancamentoAjuste(null, null, ajuste.valor(), ajuste.contaTecnica()), anterior,
                ocorrenciasCandidatas, comprasCandidatas, valeAte, proximoPasso);
    }
}
