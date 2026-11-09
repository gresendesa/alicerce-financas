package tools.alicerce.financeiro.dominio;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.alicerce.convencoes.ErroTipado;

/** Um intervalo de datas, com as duas pontas incluídas. */
public record Periodo(LocalDate de, LocalDate ate) {

    private static final Pattern ANO = Pattern.compile("(\\d{4})");
    private static final Pattern MES = Pattern.compile("(\\d{4})-(\\d{2})");
    private static final Pattern SEMANA = Pattern.compile("(\\d{4})-W(\\d{2})");
    private static final String FORMATO = "Período inválido: use 2026-09, 2026-W38 ou 2026.";

    public Periodo {
        if (de.isAfter(ate)) {
            throw ErroTipado.validacao("de", "A data inicial é depois da final.");
        }
    }

    /** Lê um mês ({@code 2026-09}), uma semana ISO ({@code 2026-W38}) ou um ano ({@code 2026}). */
    public static Periodo ler(String texto, String campo) {
        String limpo = texto.strip();
        Matcher ano = ANO.matcher(limpo);
        if (ano.matches()) {
            int numero = Integer.parseInt(limpo);
            return new Periodo(LocalDate.of(numero, 1, 1), LocalDate.of(numero, 12, 31));
        }
        Matcher mes = MES.matcher(limpo);
        if (mes.matches() && Integer.parseInt(mes.group(2)) >= 1 && Integer.parseInt(mes.group(2)) <= 12) {
            YearMonth lido = YearMonth.of(Integer.parseInt(mes.group(1)), Integer.parseInt(mes.group(2)));
            return new Periodo(lido.atDay(1), lido.atEndOfMonth());
        }
        Matcher semana = SEMANA.matcher(limpo);
        if (semana.matches()) {
            return semana(Integer.parseInt(semana.group(1)), Integer.parseInt(semana.group(2)), campo);
        }
        throw ErroTipado.validacao(campo, FORMATO);
    }

    private static Periodo semana(int ano, int numero, String campo) {
        // 4 de janeiro está sempre na primeira semana ISO do ano.
        LocalDate referencia = LocalDate.of(ano, 1, 4);
        if (numero < 1 || numero > referencia.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).getMaximum()) {
            throw ErroTipado.validacao(campo, FORMATO);
        }
        LocalDate segunda = referencia.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, numero)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Periodo(segunda, segunda.plusDays(6));
    }

    /**
     * O período anterior equivalente. Meses inteiros comparam com o mesmo número de meses inteiros, para
     * setembro comparar com agosto todo, e não com 30 dias; o resto, com o mesmo número de dias.
     */
    public Periodo anterior() {
        boolean mesesInteiros = de.getDayOfMonth() == 1 && ate.equals(ate.with(TemporalAdjusters.lastDayOfMonth()));
        if (mesesInteiros) {
            long meses = ChronoUnit.MONTHS.between(YearMonth.from(de), YearMonth.from(ate)) + 1;
            YearMonth primeiro = YearMonth.from(de).minusMonths(meses);
            return new Periodo(primeiro.atDay(1), de.minusDays(1));
        }
        long dias = ChronoUnit.DAYS.between(de, ate) + 1;
        return new Periodo(de.minusDays(dias), de.minusDays(1));
    }
}
