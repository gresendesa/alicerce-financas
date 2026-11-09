package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * A projeção de {@code projetar}, como o assistente a vê e o retrato a guarda. Todos os números saem do domínio, em
 * texto com duas casas na moeda do dono. Em cada mês há duas estimativas: {@code p50} (otimista, a mediana do gasto
 * variável) e {@code p80} (pessimista, o percentil 80 de cada categoria somado).
 *
 * @param ate o último dia do horizonte
 * @param saldoPartida a soma das contas líquidas (corrente, carteira, poupança) na moeda do dono, hoje, sem as contas reservadas a metas
 * @param estimativaVariavel há meses de referência suficientes para estimar o gasto variável; sem ela, os saldos não o descontam
 * @param piso o piso declarado com {@code declarar}; ausente sem declaração
 * @param linhaBase a projeção sem hipóteses
 * @param cenarios cada cenário pedido, com a diferença para a linha de base
 * @param regras a reserva mínima, o teto de parcelas e as metas protegidas declarados; ausente em retrato anterior às regras do perfil
 */
public record ProjecaoVista(String calculadoEm, String hoje, String ate, int horizonteMeses, String moeda, String saldoPartida,
        List<CortesVisto.ContaPartida> contasPartida, boolean estimativaVariavel, int mesesReferencia, int mesesPedidos, Piso piso,
        CenarioVisto linhaBase, List<CenarioVisto> cenarios, List<CortesVisto.Ressalva> ressalvas, List<String> notas, Regras regras) {

    /** @param saldoMinimo o saldo mínimo declarado para o fim de cada mês */
    public record Piso(String saldoMinimo, String operacaoDeclaracao) {}

    /**
     * As regras declaradas que a projeção vigia; cada uma ausente sem declaração.
     *
     * @param metasProtegidas vazia sem declaração
     */
    public record Regras(ReservaVista reservaMinima, TetoVisto tetoParcelas, List<MetaProtegidaVista> metasProtegidas) {}

    /**
     * @param valor o valor que vale; em meses, os meses vezes o gasto típico. Ausente em meses sem gasto típico
     * @param mesesDeGasto ausente na reserva por valor
     * @param gastoMensalTipico em meses: a mediana do total de saídas dos meses de referência
     * @param mesesGastoTipico em meses: os meses de que o gasto típico veio
     */
    public record ReservaVista(String valor, Integer mesesDeGasto, String gastoMensalTipico, List<String> mesesGastoTipico,
            String operacaoDeclaracao) {}

    /** @param percentual o máximo da renda do mês que as parcelas podem levar */
    public record TetoVisto(String percentual, String operacaoDeclaracao) {}

    public record MetaProtegidaVista(String id, String url, String nome, String dataAlvo) {}

    /**
     * Uma regra declarada violada num mês do cenário, pelo saldo final pessimista (p80).
     *
     * @param regra {@code piso_periodo}, {@code reserva_minima}, {@code teto_parcelas} ou {@code meta_protegida}
     * @param saldoFinalP80 em piso, reserva e meta
     * @param limite em piso e reserva, o declarado; em meta, o piso (zero sem piso) mais o aporte mensal necessário dela
     * @param comprometimento em teto: as parcelas sobre a renda do mês, em %
     * @param teto em teto: o declarado, em %
     */
    public record Violacao(String regra, String mes, String saldoFinalP80, String limite, String parcelas, String renda,
            String comprometimento, String teto, String meta, String urlMeta, String nomeMeta, String aporteMensalNecessario) {}

    /**
     * @param hipoteses vazia na linha de base
     * @param diferencaLinhaBase ausente na própria linha de base
     * @param metas cada meta ativa, com a data em que alcança o alvo neste cenário; na linha de base não há aporte planejado
     * @param violacoes as regras declaradas violadas no cenário, por regra e mês; vazia se nenhuma
     */
    public record CenarioVisto(String rotulo, List<HipoteseVista> hipoteses, List<MesVisto> meses, Indicadores indicadores,
            DiferencaVista diferencaLinhaBase, List<MetaProjetada> metas, List<Violacao> violacoes) {}

    /**
     * Quando uma meta alcança o alvo pelo progresso de hoje mais os aportes planejados no cenário.
     *
     * @param aportesPlanejados a soma dos aportes planejados nela dentro do horizonte
     * @param mesProjetado o primeiro mês em que alcança; ausente se não alcança no horizonte
     * @param noPrazo o mês projetado não passa do mês da data alvo; ausente sem data alvo ou sem mês projetado
     * @param situacao em palavras, para a tela: no prazo, depois da data alvo, sem data alvo ou não alcança no horizonte
     */
    public record MetaProjetada(String id, String url, String nome, String valorAlvo, String dataAlvo, String progresso,
            String aportesPlanejados, String mesProjetado, boolean alcancaNoHorizonte, Boolean noPrazo, String situacao) {}

    /**
     * Uma hipótese como o servidor a entendeu.
     *
     * @param resumo o que foi simulado, em palavras
     * @param itens os fluxos que ela gera no horizonte: cada parcela na fatura em que vence, cada ocorrência de compromisso
     */
    public record HipoteseVista(String tipo, String resumo, List<ItemHipotese> itens) {}

    /** @param destino onde o valor entra: a fatura, o meio ou a ocorrência */
    public record ItemHipotese(String data, String valor, String destino) {}

    /**
     * @param entradas as ocorrências de renda e as mudanças de renda
     * @param saidasConhecidas as ocorrências a pagar (atrasadas no mês corrente) e as compras simuladas fora do cartão
     * @param faturas o que vence nas faturas no mês: o restante das gravadas e as parcelas simuladas
     * @param aportes os aportes planejados em metas no mês, que saem do saldo
     * @param variavelP50 o gasto variável estimado pela mediana, com a parte do cartão no mês em que a fatura vence; ausente sem estimativa
     * @param abaixoPisoP50 o saldo final otimista fica abaixo do piso declarado
     * @param renda as entradas do mês; no corrente, mais as já recebidas
     * @param parcelas as parcelas que vencem no mês: já estão dentro de faturas e saídas, nunca some
     * @param comprometimento parcelas sobre renda, em %; ausente sem renda
     * @param abaixoReservaP50 o saldo final otimista fica abaixo da reserva mínima declarada
     */
    public record MesVisto(String mes, String saldoInicialP50, String saldoInicialP80, String entradas, String saidasConhecidas, String faturas,
            String aportes, String variavelP50, String variavelP80, String saldoFinalP50, String saldoFinalP80, boolean abaixoPisoP50, boolean abaixoPisoP80,
            String renda, String parcelas, String comprometimento, boolean abaixoReservaP50, boolean abaixoReservaP80) {}

    /**
     * @param mesesAbaixoPisoP50 vazia sem piso declarado
     * @param picoFaturas o mês em que as faturas mais pesam sobre as entradas; ausente se nenhum mês tem as duas
     * @param mesesAbaixoReservaP50 vazia sem reserva mínima com valor
     * @param picoParcelas o mês em que as parcelas mais pesam sobre a renda; ausente se nenhum mês tem as duas
     * @param mesesSemRenda os meses sem renda, em que o comprometimento não é avaliado
     */
    public record Indicadores(Menor menorSaldoP50, Menor menorSaldoP80, List<String> mesesAbaixoPisoP50, List<String> mesesAbaixoPisoP80,
            Pico picoFaturas, List<String> mesesAbaixoReservaP50, List<String> mesesAbaixoReservaP80, PicoParcelas picoParcelas,
            List<String> mesesSemRenda) {}

    /** @param percentual parcelas sobre renda, em %, com duas casas */
    public record PicoParcelas(String mes, String parcelas, String renda, String percentual) {}

    /** O menor saldo final do horizonte e o primeiro mês em que acontece. */
    public record Menor(String saldo, String mes) {}

    /** @param percentual faturas sobre entradas, em %, com duas casas */
    public record Pico(String mes, String faturas, String entradas, String percentual) {}

    /** Cenário menos linha de base: positivo é mais dinheiro no cenário. */
    public record DiferencaVista(String saldoFinalP50, String saldoFinalP80, String menorSaldoP50, String menorSaldoP80,
            List<DiferencaMesVista> meses) {}

    public record DiferencaMesVista(String mes, String saldoFinalP50, String saldoFinalP80) {}
}
