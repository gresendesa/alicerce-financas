package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Uma declaração do perfil, como o assistente a vê. Só os campos do tipo vêm preenchidos.
 *
 * @param periodo em {@code piso_periodo}: sempre {@code mes}
 * @param saldoMinimo em {@code piso_periodo}: o saldo mínimo no último dia do mês
 * @param contasPagas em {@code piso_periodo}: as faturas e os compromissos do mês entram pagos; sempre {@code true}
 * @param categorias em {@code categorias_essenciais}: as categorias, vazia se nenhuma é essencial
 * @param valor em {@code reserva_minima} por valor: o valor declarado
 * @param mesesDeGasto em {@code reserva_minima} por meses: quantos meses de gasto típico
 * @param valorEquivalente em {@code reserva_minima}: o valor que vale hoje; em meses, os meses vezes o gasto típico, ausente sem ele
 * @param gastoMensalTipico em {@code reserva_minima} por meses: a mediana do total de saídas dos meses de referência
 * @param mesesGastoTipico em {@code reserva_minima} por meses: os meses de referência de que o gasto típico veio
 * @param percentual em {@code intencao_poupanca} e {@code teto_parcelas}: o percentual da renda
 * @param metas em {@code meta_protegida}: as metas, vazia se nenhuma é protegida
 * @param texto em {@code nota}: o texto da nota
 * @param ressalva o que impede o valor de sair, como a falta de meses de referência
 * @param operacao o id curto da operação que a gravou: desfazê-la traz de volta a anterior
 */
public record DeclaracaoVista(String tipo, String vigenteDesde, String registradaEm, String periodo, String saldoMinimo, Boolean contasPagas,
        List<CategoriaDeclarada> categorias, String valor, Integer mesesDeGasto, String valorEquivalente, String gastoMensalTipico,
        List<String> mesesGastoTipico, String percentual, List<MetaDeclarada> metas, String texto, String ressalva, String observacao,
        String operacao, String urlOperacao) {

    /** @param id o id curto da categoria; ausente se ela deixou de existir */
    public record CategoriaDeclarada(String id, String nome, boolean arquivada) {}

    /**
     * @param id o id curto da meta; ausente se ela foi arquivada
     * @param situacao a situação de hoje: uma meta concluída ou encerrada deixa de ser vigiada
     */
    public record MetaDeclarada(String id, String url, String nome, String dataAlvo, String situacao) {}
}
