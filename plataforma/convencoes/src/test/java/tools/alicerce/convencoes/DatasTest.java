package tools.alicerce.convencoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DatasTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Test
    void hojeEhNoFusoDoDonoENaoNoDoServidor() {
        // 02:00 UTC de 30/10 ainda é 29/10 em São Paulo.
        Clock relogio = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneOffset.UTC);
        assertThat(Datas.hoje(relogio, SAO_PAULO)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(Datas.lerOuHoje(null, "data", relogio, SAO_PAULO)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(Datas.lerOuHoje("2026-01-05", "data", relogio, SAO_PAULO)).isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void leDataEPeriodoIso() {
        assertThat(Datas.ler("2026-02-28", "data")).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Datas.lerPeriodo("2026-10", "periodo")).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void recusaFormatosForaDoIso() {
        assertThatThrownBy(() -> Datas.ler("29/10/2026", "data"))
                .isInstanceOfSatisfying(ErroTipado.class, e -> assertThat(e.campo()).isEqualTo("data"));
        assertThatThrownBy(() -> Datas.ler("2026-02-30", "data")).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> Datas.lerPeriodo("10/2026", "periodo")).isInstanceOf(ErroTipado.class);
    }
}
