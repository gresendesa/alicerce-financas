package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * As contas de uma meta de valor: quanto falta e quanto aportar por mês para chegar na data.
 * Tudo em centavos da moeda da meta.
 */
public final class CalculoMeta {

    private CalculoMeta() {}

    /**
     * O retrato da meta hoje.
     *
     * @param progresso aportes menos resgates; pode passar do alvo, e é negativo só se resgatou mais do que aportou
     * @param falta o alvo menos o progresso, nunca negativo
     * @param mesesRestantes os meses civis de hoje até a data alvo, o de hoje e o da data inclusive; ao menos 1. Nulo sem data
     * @param aporteMensal a falta dividida pelos meses restantes, arredondada para cima: aportado todo mês, chega na data.
     *     Nulo sem data alvo; zero se já alcançou
     * @param prazoVencido a data alvo já passou e ainda falta: o aporte mensal é a falta inteira
     * @param percentual o progresso sobre o alvo, em %, com duas casas, entre 0 e 100
     */
    public record Situacao(long progresso, long falta, Integer mesesRestantes, Long aporteMensal, boolean prazoVencido, boolean alcancada,
            BigDecimal percentual) {}

    public static Situacao calcular(Meta meta, long progresso, LocalDate hoje) {
        long falta = Math.max(0, Math.subtractExact(meta.alvoCentavos(), progresso));
        boolean alcancada = falta == 0;
        BigDecimal percentual = BigDecimal.valueOf(Math.max(0, Math.min(progresso, meta.alvoCentavos()))).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(meta.alvoCentavos()), 2, RoundingMode.DOWN);
        if (meta.dataAlvo() == null) {
            return new Situacao(progresso, falta, null, null, false, alcancada, percentual);
        }
        int meses = mesesAte(hoje, meta.dataAlvo());
        boolean vencido = !alcancada && meta.dataAlvo().isBefore(hoje);
        return new Situacao(progresso, falta, meses, aporteMensal(falta, meses), vencido, alcancada, percentual);
    }

    /** Os meses civis de hoje até a data, contando os dois; ao menos 1, mesmo com a data já passada. */
    public static int mesesAte(LocalDate hoje, LocalDate data) {
        long distancia = YearMonth.from(hoje).until(YearMonth.from(data), ChronoUnit.MONTHS);
        return (int) Math.max(1, distancia + 1);
    }

    /** A falta em partes iguais pelos meses, arredondada para cima ao centavo: a soma nunca fica abaixo da falta. */
    public static long aporteMensal(long falta, int meses) {
        if (meses < 1) {
            throw new IllegalArgumentException("Meses começa em 1");
        }
        return falta <= 0 ? 0 : Math.ceilDiv(falta, meses);
    }

    /**
     * Um plano de aportes mês a mês que para quando a meta chega no alvo: o último leva só o que faltava. Sem isso, a
     * projeção tiraria do saldo dinheiro que o dono não precisaria guardar.
     *
     * @param progresso o progresso de hoje
     * @param mensal o aporte planejado por mês
     * @param meses quantos meses o plano dura
     * @return o aporte de cada mês, zero depois de alcançar
     */
    public static long[] planoDeAportes(long progresso, long alvo, long mensal, int meses) {
        if (mensal <= 0) {
            throw new IllegalArgumentException("Aporte planejado tem de ser positivo");
        }
        long[] plano = new long[meses];
        long falta = Math.max(0, Math.subtractExact(alvo, progresso));
        for (int i = 0; i < meses && falta > 0; i++) {
            plano[i] = Math.min(mensal, falta);
            falta -= plano[i];
        }
        return plano;
    }
}
