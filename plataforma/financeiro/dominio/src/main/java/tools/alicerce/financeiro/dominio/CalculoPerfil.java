package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.alicerce.convencoes.ErroTipado;

/**
 * As contas das regras do perfil: o gasto mensal típico e a reserva em meses, o
 * comprometimento da renda com parcelas, a poupança observada, as parcelas subindo, a meta atrasada e a variabilidade de
 * uma categoria. Tudo em centavos; percentuais em {@link BigDecimal} com duas casas. Os limiares são fixos.
 */
public final class CalculoPerfil {

    /** O máximo de meses de gasto numa reserva mínima. */
    public static final int MESES_RESERVA_MAXIMOS = 24;
    /** O tamanho máximo da nota do perfil. */
    public static final int NOTA_MAXIMA = 1000;
    /** O horizonte de {@code situacao} sem pedido. */
    public static final int HORIZONTE_SITUACAO = 3;
    /** Quantos meses fechados a poupança observada e o ritmo dos aportes olham. */
    public static final int MESES_OBSERVADOS = CalculoSituacao.MESES_TENDENCIA;
    /** Quantas altas seguidas fazem {@code parcelas_subindo}: os meses fechados e o corrente. */
    public static final int ALTAS_PARCELAS = 3;

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final int ESCALA = 2;

    private CalculoPerfil() {}

    /** Um percentual declarado: de {@code minimo} a 100, com até duas casas. */
    static BigDecimal percentual(BigDecimal valor, BigDecimal minimo, String campo) {
        if (valor == null) {
            throw ErroTipado.validacao(campo, "Falta o percentual.");
        }
        BigDecimal limpo = valor.stripTrailingZeros();
        if (limpo.scale() > ESCALA || valor.compareTo(minimo) < 0 || valor.compareTo(CEM) > 0) {
            throw ErroTipado.validacao(campo, "O percentual vai de " + minimo.toPlainString() + " a 100, com até duas casas.");
        }
        return valor.setScale(ESCALA, RoundingMode.UNNECESSARY);
    }

    /**
     * O gasto mensal típico: a mediana do total de saídas em competência, fixos incluídos, dos meses de referência de
     * {@code ritmo_gastos}. Nulo com menos de {@value CalculoRitmo#MESES_MINIMOS} meses.
     *
     * @param totais o total de cada mês de referência válido
     */
    public static Long gastoMensalTipico(List<Long> totais) {
        return totais.size() < CalculoRitmo.MESES_MINIMOS ? null : CalculoRitmo.mediana(totais);
    }

    /** O valor da reserva mínima: o declarado, ou os meses vezes o gasto típico; nulo em meses sem gasto típico. */
    public static Long reserva(ValorDeclaracao.ReservaMinima r, Long gastoTipico) {
        if (r.valorCentavos() != null) {
            return r.valorCentavos();
        }
        return gastoTipico == null ? null : Math.multiplyExact(gastoTipico, (long) r.mesesDeGasto());
    }

    /** Parcelas sobre renda, em %, com duas casas; nulo sem renda: o mês não é avaliado (decisão 2). */
    public static BigDecimal comprometimento(long parcelas, long renda) {
        return renda <= 0 ? null : BigDecimal.valueOf(parcelas).multiply(CEM).divide(BigDecimal.valueOf(renda), ESCALA, RoundingMode.HALF_EVEN);
    }

    /** Acima do teto: o comprometimento passa dele. Sem comprometimento, não é avaliado. */
    public static boolean acimaDoTeto(BigDecimal comprometimento, BigDecimal teto) {
        return comprometimento != null && comprometimento.compareTo(teto) > 0;
    }

    /**
     * A poupança de um período.
     *
     * @param percentual (renda − saídas) ÷ renda, em %, com duas casas; negativo se gastou mais do que ganhou; nulo sem renda
     */
    public record Poupanca(long renda, long saidas, BigDecimal percentual) {}

    public static Poupanca poupanca(long renda, long saidas) {
        return new Poupanca(renda, saidas, renda <= 0 ? null
                : BigDecimal.valueOf(renda - saidas).multiply(CEM).divide(BigDecimal.valueOf(renda), ESCALA, RoundingMode.HALF_EVEN));
    }

    /** {@code poupanca_abaixo}: a observada fica abaixo da declarada. "Não poupar" (0 %) nunca dispara; sem renda, não é avaliado. */
    public static boolean poupancaAbaixo(Poupanca observada, BigDecimal declarada) {
        return declarada.signum() > 0 && observada.percentual() != null && observada.percentual().compareTo(declarada) < 0;
    }

    /**
     * {@code reserva_abaixo_projetada}: a severidade pelo primeiro mês do horizonte abaixo da reserva. Crítico se é o mês
     * corrente ou o seguinte; aviso depois.
     *
     * @param indice a posição do primeiro mês abaixo no horizonte, 0 no mês corrente
     */
    public static CalculoSituacao.Severidade severidadeReserva(int indice) {
        return indice <= 1 ? CalculoSituacao.Severidade.CRITICO : CalculoSituacao.Severidade.AVISO;
    }

    /**
     * {@code parcelas_subindo}: o valor das parcelas cresceu em {@value #ALTAS_PARCELAS} meses seguidos.
     *
     * @param porMes as parcelas dos meses fechados e do corrente, do mais antigo ao mais recente
     */
    public static boolean parcelasSubindo(List<Long> porMes) {
        if (porMes.size() != ALTAS_PARCELAS + 1) {
            throw new IllegalArgumentException("São " + (ALTAS_PARCELAS + 1) + " meses");
        }
        for (int i = 1; i < porMes.size(); i++) {
            if (porMes.get(i) <= porMes.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Uma meta pelo ritmo dos aportes líquidos dos meses fechados.
     *
     * @param ritmoMensal os aportes menos os resgates dos {@value #MESES_OBSERVADOS} meses fechados, divididos por eles, para baixo
     * @param aporteNecessario o mesmo de {@link CalculoMeta}: a falta pelos meses de hoje até a data alvo
     * @param mesProjetado o mês em que chega no ritmo de agora; nulo se o ritmo não é positivo
     */
    public record RitmoMeta(long progresso, long falta, long aportesLiquidos, long ritmoMensal, long aporteNecessario, boolean prazoVencido,
            YearMonth mesProjetado) {}

    /**
     * {@code meta_atrasada}: uma meta ativa com data alvo que ainda não chegou, e cujo ritmo dos aportes líquidos não alcança o
     * aporte mensal necessário até a data, ou cuja data já passou (decisão 3).
     *
     * @param aportesLiquidos aportes menos resgates nos {@value #MESES_OBSERVADOS} meses fechados
     */
    public static Optional<RitmoMeta> metaAtrasada(Meta meta, long progresso, long aportesLiquidos, LocalDate hoje) {
        Objects.requireNonNull(meta, "meta");
        if (!meta.emAndamento() || meta.dataAlvo() == null) {
            return Optional.empty();
        }
        CalculoMeta.Situacao s = CalculoMeta.calcular(meta, progresso, hoje);
        if (s.alcancada()) {
            return Optional.empty();
        }
        long ritmo = Math.floorDiv(aportesLiquidos, MESES_OBSERVADOS);
        YearMonth projetado = ritmo <= 0 ? null : YearMonth.from(hoje).plusMonths(Math.ceilDiv(s.falta(), ritmo) - 1);
        boolean atrasada = s.prazoVencido() || ritmo < s.aporteMensal();
        return atrasada ? Optional.of(new RitmoMeta(progresso, s.falta(), aportesLiquidos, ritmo, s.aporteMensal(), s.prazoVencido(), projetado))
                : Optional.empty();
    }

    /** Severidade de {@code meta_atrasada}: crítica se a meta é protegida. */
    public static CalculoSituacao.Severidade severidadeMeta(boolean protegida) {
        return protegida ? CalculoSituacao.Severidade.CRITICO : CalculoSituacao.Severidade.AVISO;
    }

    /**
     * A variabilidade de uma categoria nos meses de referência (decisão 5).
     *
     * @param dispersao (p75 − p25) ÷ mediana, em %, com duas casas; nula se a mediana é zero
     */
    public record Variabilidade(long menor, long mediana, long maior, long p25, long p75, BigDecimal dispersao) {}

    /** Os percentis pelo posto mais próximo, como no resto do cálculo; sem raiz quadrada nem {@code double}. */
    public static Variabilidade variabilidade(List<Long> porMes) {
        if (porMes.isEmpty()) {
            throw new IllegalArgumentException("Variabilidade sem meses.");
        }
        List<Long> ordenados = porMes.stream().sorted().toList();
        long mediana = CalculoRitmo.mediana(ordenados);
        long p25 = CalculoCortes.percentil(ordenados, 25);
        long p75 = CalculoCortes.percentil(ordenados, 75);
        BigDecimal dispersao = mediana == 0 ? null
                : BigDecimal.valueOf(p75 - p25).multiply(CEM).divide(BigDecimal.valueOf(mediana), ESCALA, RoundingMode.HALF_EVEN);
        return new Variabilidade(ordenados.get(0), mediana, ordenados.get(ordenados.size() - 1), p25, p75, dispersao);
    }
}
