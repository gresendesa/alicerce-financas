package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Resultado de {@code registrar_gasto} e {@code registrar_ganho}: tudo o que foi gravado.
 *
 * @param id o id curto; {@code null} numa simulação, que não grava nada
 * @param comprovantePendente o lançamento veio de imagem e espera o arquivo
 * @param urlAnexo o link de envio. Nunca é gravado no resultado da operação: só o hash do token fica no
 *     banco, e por isso uma repetição volta sem ele; {@code anexar_comprovante} gera outro
 * @param urlAnexoValidaAte até quando o link vale
 * @param original como a compra aconteceu em outra moeda; {@code valor} continua sendo o que vale
 * @param credito como a compra ficou no cartão; {@code valor} é o gasto inteiro, com as parcelas dentro dele
 * @param procedencia {@code exogeno} se o fato é anterior ao marco: {@code saldoConta} não mudou
 * @param ocorrencia a ocorrência de compromisso que este lançamento pagou; ausente se não pagou nenhuma
 */
public record LancamentoRegistrado(String id, String url, String tipo, String valor, String moeda, String data,
        String descricao, String meioPagamento, String conta, String saldoConta, boolean categoriaInferida,
        boolean ambitoInferido, List<MovimentoRegistrado> movimentos, Boolean comprovantePendente, String urlAnexo,
        String urlAnexoValidaAte, OriginalVisto original, CreditoVisto credito, String procedencia,
        OcorrenciaVinculada ocorrencia) {

    /** Um movimento, com nomes em vez de ids. @param parcela {@code 2/3}; ausente fora de compra parcelada */
    public record MovimentoRegistrado(String conta, String valor, String ambito, String parcela) {}

    LancamentoRegistrado simulado() {
        return new LancamentoRegistrado(null, null, tipo, valor, moeda, data, descricao, meioPagamento, conta, saldoConta,
                categoriaInferida, ambitoInferido, movimentos, comprovantePendente, null, null, original, credito, procedencia, ocorrencia);
    }

    LancamentoRegistrado comOcorrencia(OcorrenciaVinculada vinculada) {
        return new LancamentoRegistrado(id, url, tipo, valor, moeda, data, descricao, meioPagamento, conta, saldoConta,
                categoriaInferida, ambitoInferido, movimentos, comprovantePendente, urlAnexo, urlAnexoValidaAte, original, credito, procedencia,
                vinculada);
    }

    LancamentoRegistrado comLink(String url, String validaAte) {
        return new LancamentoRegistrado(id, this.url, tipo, valor, moeda, data, descricao, meioPagamento, conta, saldoConta,
                categoriaInferida, ambitoInferido, movimentos, comprovantePendente, url, validaAte, original, credito, procedencia, ocorrencia);
    }
}
