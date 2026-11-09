package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Os sinais de {@code situacao}: quando cada um dispara e com que severidade, por regra fixa. Os limiares
 * são os da modelagem e continuam fixos; os sinais do perfil estão em {@link CalculoPerfil}. Nenhum número é
 * recalculado aqui: o restante vem de {@link CalculoCompromisso} e da fatura, o prazo de {@link PrazoConferencia}
 * e os meses de {@link CalculoResumo}.
 */
public final class CalculoSituacao {

    /** Fatura com restante que vence em até tantos dias. */
    public static final int DIAS_FATURA = 5;
    /** Fatura que vence em até tantos dias é crítica: hoje ou amanhã. */
    public static final int DIAS_FATURA_CRITICA = 1;
    /** Ocorrência atrasada há mais de tantos dias é crítica. */
    public static final int DIAS_ATRASO_CRITICO = 30;
    /** Quantos meses fechados a tendência de categoria olha. */
    public static final int MESES_TENDENCIA = 3;
    /** A alta mínima, em percentual, de cada mês sobre o anterior. */
    public static final BigDecimal ALTA_MENSAL = BigDecimal.TEN;

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final int ESCALA_PERCENTUAL = 2;

    private CalculoSituacao() {}

    public enum Codigo {
        COMPROMISSO_ATRASADO,
        FATURA_VENCENDO,
        CONFERENCIA_PENDENTE,
        CONFERENCIA_ANTIGA,
        CATEGORIA_SUBINDO,
        /** O saldo final p80 de um mês do horizonte fica abaixo da reserva mínima. */
        RESERVA_ABAIXO_PROJETADA,
        /** As parcelas de um mês do horizonte passam do teto declarado. */
        PARCELAS_ACIMA_TETO,
        /** As parcelas cresceram em 3 meses seguidos. */
        PARCELAS_SUBINDO,
        /** A poupança dos 3 meses fechados fica abaixo da declarada. */
        POUPANCA_ABAIXO,
        /** Uma meta com data alvo não chega nela no ritmo dos aportes. */
        META_ATRASADA;

        public String codigo() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Na ordem em que os sinais saem: o mais grave primeiro. */
    public enum Severidade {
        CRITICO,
        AVISO,
        INFO;

        public String codigo() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Dias corridos de {@code de} até {@code ate}; negativo se {@code ate} já passou. */
    public static long dias(LocalDate de, LocalDate ate) {
        return ChronoUnit.DAYS.between(de, ate);
    }

    /**
     * {@code compromisso_atrasado}: a ocorrência venceu e tem valor em aberto, inclusive a paga em parte.
     * Crítico depois de {@value #DIAS_ATRASO_CRITICO} dias de atraso.
     */
    public static Optional<Severidade> compromissoAtrasado(Ocorrencia o, LocalDate hoje) {
        if (o.situacao() != SituacaoOcorrencia.ATRASADA || o.valorRestanteCentavos() == null || o.valorRestanteCentavos() <= 0) {
            return Optional.empty();
        }
        return Optional.of(dias(o.vencimento(), hoje) > DIAS_ATRASO_CRITICO ? Severidade.CRITICO : Severidade.AVISO);
    }

    /**
     * {@code fatura_vencendo}: a fatura tem restante e vence em até {@value #DIAS_FATURA} dias. Crítica se
     * vence hoje ou amanhã, ou se já venceu e continua com restante.
     */
    public static Optional<Severidade> faturaVencendo(LocalDate venceEm, long restanteCentavos, LocalDate hoje) {
        long dias = dias(hoje, venceEm);
        if (restanteCentavos <= 0 || dias > DIAS_FATURA) {
            return Optional.empty();
        }
        return Optional.of(dias <= DIAS_FATURA_CRITICA ? Severidade.CRITICO : Severidade.AVISO);
    }

    /** {@code conferencia_pendente}: a última conferência da conta deixou diferença sem explicação nem ajuste. */
    public static Optional<Severidade> conferenciaPendente(PrazoConferencia.Situacao ultima) {
        return ultima == PrazoConferencia.Situacao.PENDENTE ? Optional.of(Severidade.AVISO) : Optional.empty();
    }

    /** {@code conferencia_antiga}: a conta passou do prazo de {@value PrazoConferencia#DIAS} dias, como em {@code conferencias_vencidas}. */
    public static Optional<Severidade> conferenciaAntiga(LocalDate hoje, LocalDate ultima, LocalDate marco) {
        return PrazoConferencia.vencida(hoje, ultima, marco) ? Optional.of(Severidade.INFO) : Optional.empty();
    }

    /** Os {@value #MESES_TENDENCIA} meses fechados antes do mês de hoje, do mais antigo ao mais recente. */
    public static List<YearMonth> mesesFechados(LocalDate hoje) {
        YearMonth atual = YearMonth.from(hoje);
        List<YearMonth> meses = new ArrayList<>();
        for (int i = MESES_TENDENCIA; i >= 1; i--) {
            meses.add(atual.minusMonths(i));
        }
        return meses;
    }

    /**
     * A tendência de uma categoria nos meses fechados.
     *
     * @param saidasCentavos o gasto de cada mês, do mais antigo ao mais recente
     * @param altas o percentual de cada mês sobre o anterior; uma a menos que os meses
     */
    public record Tendencia(List<Long> saidasCentavos, List<BigDecimal> altas) {

        public Tendencia {
            saidasCentavos = List.copyOf(saidasCentavos);
            altas = List.copyOf(altas);
        }
    }

    /**
     * {@code categoria_subindo}: nos {@value #MESES_TENDENCIA} meses fechados, a categoria teve gasto em
     * todos e cada mês passou o anterior em mais de {@code ALTA_MENSAL}%. Um mês sem gasto, a categoria nova
     * (sem os três meses) e o pico isolado não disparam; estornos e anulados já ficam fora dos meses.
     */
    public static Optional<Tendencia> categoriaSubindo(List<Long> saidasCentavos) {
        if (saidasCentavos.size() != MESES_TENDENCIA || saidasCentavos.stream().anyMatch(v -> v == null || v <= 0)) {
            return Optional.empty();
        }
        List<BigDecimal> altas = new ArrayList<>();
        for (int i = 1; i < saidasCentavos.size(); i++) {
            BigDecimal anterior = BigDecimal.valueOf(saidasCentavos.get(i - 1));
            BigDecimal alta = BigDecimal.valueOf(saidasCentavos.get(i)).subtract(anterior).multiply(CEM)
                    .divide(anterior, ESCALA_PERCENTUAL, RoundingMode.HALF_EVEN);
            if (alta.compareTo(ALTA_MENSAL) <= 0) {
                return Optional.empty();
            }
            altas.add(alta);
        }
        return Optional.of(new Tendencia(saidasCentavos, altas));
    }

    /** Severidade de {@code categoria_subindo}: sempre informativa. */
    public static Severidade severidadeCategoria() {
        return Severidade.INFO;
    }
}
