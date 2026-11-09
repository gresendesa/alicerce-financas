package tools.alicerce.convencoes;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Datas da interface: ISO {@code YYYY-MM-DD} e períodos {@code YYYY-MM}, sempre no fuso do dono.
 * Data omitida vale hoje no fuso do dono, nunca no do servidor.
 */
public final class Datas {

    private Datas() {}

    public static LocalDate hoje(Clock relogio, ZoneId fuso) {
        return LocalDate.now(relogio.withZone(fuso));
    }

    public static LocalDate ler(String texto, String campo) {
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ErroTipado.validacao(campo, "Data inválida: use o formato AAAA-MM-DD, como \"2026-10-29\".");
        }
    }

    /** A data informada ou, se omitida, hoje no fuso do dono. */
    public static LocalDate lerOuHoje(String texto, String campo, Clock relogio, ZoneId fuso) {
        return texto == null || texto.isBlank() ? hoje(relogio, fuso) : ler(texto, campo);
    }

    public static YearMonth lerPeriodo(String texto, String campo) {
        try {
            return YearMonth.parse(texto);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ErroTipado.validacao(campo, "Período inválido: use o formato AAAA-MM, como \"2026-10\".");
        }
    }
}
