package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PrazoConferenciaTest {

    private static LocalDate d(String texto) {
        return LocalDate.parse(texto);
    }

    @Test
    void venceDepoisDeTrintaDiasDaUltimaConferencia() {
        assertThat(PrazoConferencia.valeAte(d("2026-10-01"), d("2026-01-01"))).isEqualTo("2026-10-31");
        assertThat(PrazoConferencia.vencida(d("2026-10-31"), d("2026-10-01"), d("2026-01-01"))).isFalse();
        assertThat(PrazoConferencia.vencida(d("2026-11-01"), d("2026-10-01"), d("2026-01-01"))).isTrue();
    }

    @Test
    void contaNuncaConferidaVenceTrintaDiasDepoisDoMarco() {
        assertThat(PrazoConferencia.vencida(d("2026-11-02"), null, d("2026-10-05"))).isFalse();
        assertThat(PrazoConferencia.vencida(d("2026-11-05"), null, d("2026-10-05"))).isTrue();
        assertThat(PrazoConferencia.vencida(d("2026-11-05"), null, null)).isFalse();
    }

    @Test
    void situacaoSaiDaDiferencaEDoAjuste() {
        assertThat(PrazoConferencia.Situacao.de(0, false)).isEqualTo(PrazoConferencia.Situacao.CONFERIDA);
        assertThat(PrazoConferencia.Situacao.de(-500, false)).isEqualTo(PrazoConferencia.Situacao.PENDENTE);
        assertThat(PrazoConferencia.Situacao.de(-500, true)).isEqualTo(PrazoConferencia.Situacao.AJUSTADA);
    }
}
