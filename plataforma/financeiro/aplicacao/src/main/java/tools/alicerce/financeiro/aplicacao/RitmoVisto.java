package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * O ritmo de gastos de um mês, como o assistente e o retrato o veem. Todo valor sai de
 * {@link tools.alicerce.financeiro.dominio.CalculoRitmo}, na moeda do dono.
 *
 * @param emCurso o mês ainda não acabou; num mês fechado não há projeções
 * @param diasDecorridos o dia do recorte: hoje no mês em curso, o último num mês fechado
 * @param projecoes ausente em mês fechado
 * @param tipico ausente com menos de 3 meses de referência válidos
 * @param responsaveis ausente sem típico
 * @param tendencia ausente se os meses fechados têm histórico parcial
 * @param variabilidade ausente sem típico (menos de 3 meses de referência)
 * @param poupanca ausente sem intenção de poupança declarada
 */
public record RitmoVisto(String calculadoEm, String mes, String hoje, String visao, String notaVisao, String moeda, boolean incluirFixos,
        String notaFixos, boolean emCurso, int diasDecorridos, int diasDoMes, String acumulado, String ritmoDiario, Projecoes projecoes,
        Tipico tipico, List<MesReferencia> mesesReferencia, Responsaveis responsaveis, Tendencia tendencia, List<Ressalva> ressalvas,
        Variabilidade variabilidade, Poupanca poupanca) {

    /** As categorias nos meses de referência, da que mais varia para a que menos. */
    public record Variabilidade(List<String> meses, List<VariabilidadeCategoria> categorias, String nota) {}

    /**
     * @param porMes o gasto de cada mês de referência, na ordem dos meses, zero onde não gastou
     * @param dispersao (p75 − p25) ÷ mediana, em %; ausente se a mediana é zero
     */
    public record VariabilidadeCategoria(String id, String url, String nome, String menor, String mediana, String maior, String dispersao,
            List<String> porMes) {}

    /**
     * A poupança dos 3 meses fechados antes de hoje contra a declarada: o mesmo número do sinal {@code poupanca_abaixo}.
     *
     * @param observada (renda − saídas) ÷ renda, em %; ausente sem renda ou com histórico parcial
     * @param abaixo a observada fica abaixo da declarada; "não poupar" (0 %) nunca fica
     */
    public record Poupanca(String declarada, String observada, String renda, String saidas, List<String> meses, boolean abaixo,
            String operacaoDeclaracao, String nota) {}

    /**
     * @param acumuladoMaisRestanteTipico ausente sem típico
     */
    public record Projecoes(String mantidoRitmoAtual, String acumuladoMaisRestanteTipico, String nota) {}

    /**
     * @param acumulado a mediana do gasto dos meses de referência até o mesmo dia
     * @param restante a mediana do que eles gastaram depois desse dia
     * @param percentual da diferença sobre o típico; ausente se o típico é zero
     */
    public record Tipico(String acumulado, String ritmoDiario, String restante, String diferenca, String percentual, int mesesUsados,
            int mesesPedidos) {}

    /** @param total o mês inteiro: com {@code incluir_fixos}, o mesmo de {@code resumo_periodo} no mês */
    public record MesReferencia(String mes, String ateODia, String total) {}

    public record Responsaveis(List<Contribuicao> categorias, List<Contribuicao> contrapartes, List<CompraAtipica> comprasAtipicas,
            String nota) {}

    /** @param id o id curto da categoria; ausente na contraparte */
    public record Contribuicao(String id, String url, String nome, String atual, String tipico, String contribuicao) {}

    public record CompraAtipica(String id, String url, String data, String descricao, String categoria, String valor, String percentil95,
            String excesso) {}

    public record Tendencia(List<String> meses, List<TendenciaCategoria> categorias, String nota) {}

    /** @param variacoes o percentual de cada mês sobre o anterior; nulo onde o anterior é zero */
    public record TendenciaCategoria(String id, String url, String nome, List<String> saidas, List<String> variacoes, boolean subindo) {}

    /**
     * @param mesesValidos quantos meses de referência têm histórico completo
     * @param historicoCompletoDesde o primeiro mês com histórico completo
     */
    public record Ressalva(String codigo, String mensagem, Integer mesesValidos, String historicoCompletoDesde) {}
}
