package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CalculoAgendaTest {

    @Test
    void separaAtrasadoDoPeriodoEEntradaDeSaida() {
        CalculoAgenda.Totais t = CalculoAgenda.totais(List.of(new CalculoAgenda.Item(true, 15000, true), new CalculoAgenda.Item(true, 30000, false),
                new CalculoAgenda.Item(false, 50000, false), new CalculoAgenda.Item(false, 1000, true)));

        assertThat(t.aPagarAtrasado()).isEqualTo(15000);
        assertThat(t.aPagarNoPeriodo()).isEqualTo(30000);
        assertThat(t.aReceberNoPeriodo()).isEqualTo(50000);
        assertThat(t.aReceberAtrasado()).isEqualTo(1000);
        assertThat(t.aPagar()).isEqualTo(45000);
        assertThat(t.aReceber()).isEqualTo(51000);
        assertThat(t.saldoPrevisto()).isEqualTo(6000);
    }

    @Test
    void semItensTudoZero() {
        assertThat(CalculoAgenda.totais(List.of())).isEqualTo(new CalculoAgenda.Totais(0, 0, 0, 0, 0, 0, 0));
    }
}
