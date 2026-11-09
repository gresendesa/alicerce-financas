package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Os parâmetros de {@code projetar} já resolvidos, como o retrato os guarda. O piso e as regras ficam como valiam no pedido:
 * a comparação com o cálculo de agora usa o mesmo dia, horizonte, piso e regras, só com os dados novos.
 *
 * @param hoje a data de hoje no fuso do dono quando a projeção foi pedida
 * @param piso o saldo mínimo declarado para o fim de cada mês, na moeda do dono; nulo sem declaração
 * @param operacaoPiso a operação que gravou a declaração do piso; nula sem declaração
 * @param cenarios os cenários como vieram, com o rótulo de cada um preenchido
 * @param regras as demais regras declaradas que a projeção vigia; nulo em retrato anterior às regras do perfil
 */
public record ParametrosProjecao(String hoje, int horizonteMeses, String piso, String operacaoPiso, List<Projetar.Cenario> cenarios,
        Regras regras) {

    public ParametrosProjecao {
        cenarios = cenarios == null ? List.of() : List.copyOf(cenarios);
        regras = regras == null ? Regras.NENHUMA : regras;
    }

    /** Só a linha de base: é o que {@code comparar_agora} recalcula. */
    ParametrosProjecao semCenarios() {
        return new ParametrosProjecao(hoje, horizonteMeses, piso, operacaoPiso, List.of(), regras);
    }

    /**
     * As regras declaradas, já resolvidas no dia do pedido.
     *
     * @param reserva o valor da reserva mínima; em meses, o equivalente pelo gasto típico. Nulo sem declaração ou sem valor
     * @param reservaMesesDeGasto em reserva por meses: quantos
     * @param gastoMensalTipico em reserva por meses: o gasto típico usado
     * @param mesesGastoTipico em reserva por meses: os meses de referência de que ele veio
     * @param reservaSemValor a reserva é em meses e não há gasto típico
     * @param teto o teto de parcelas, em % da renda
     * @param metasProtegidas os ids das metas protegidas
     */
    public record Regras(String reserva, Integer reservaMesesDeGasto, String gastoMensalTipico, List<String> mesesGastoTipico,
            boolean reservaSemValor, String operacaoReserva, String teto, String operacaoTeto, List<String> metasProtegidas,
            String operacaoMetas) {

        static final Regras NENHUMA = new Regras(null, null, null, null, false, null, null, null, List.of(), null);

        public Regras {
            mesesGastoTipico = mesesGastoTipico == null ? null : List.copyOf(mesesGastoTipico);
            metasProtegidas = metasProtegidas == null ? List.of() : List.copyOf(metasProtegidas);
        }
    }
}
