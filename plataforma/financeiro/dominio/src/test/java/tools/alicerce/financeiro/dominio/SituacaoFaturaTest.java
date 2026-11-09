package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.Dinheiro;

class SituacaoFaturaTest {

    private static final LocalDate FECHA = LocalDate.parse("2026-09-10");

    private static SituacaoFatura situacao(String hoje, long total, long pago) {
        Currency brl = Currency.getInstance("BRL");
        return SituacaoFatura.de(LocalDate.parse(hoje), FECHA, Dinheiro.deCentavos(total, brl), Dinheiro.deCentavos(pago, brl));
    }

    @Test
    void semPagamentoADataDecideEODiaDoFechamentoAindaEAberto() {
        assertThat(situacao("2026-09-10", 100, 0)).isEqualTo(SituacaoFatura.ABERTA);
        assertThat(situacao("2026-09-11", 100, 0)).isEqualTo(SituacaoFatura.FECHADA);
    }

    @Test
    void pagamentosMandamSobreAData() {
        assertThat(situacao("2026-09-01", 100, 40)).isEqualTo(SituacaoFatura.PARCIALMENTE_PAGA);
        assertThat(situacao("2026-09-20", 100, 40)).isEqualTo(SituacaoFatura.PARCIALMENTE_PAGA);
        assertThat(situacao("2026-09-20", 100, 100)).isEqualTo(SituacaoFatura.PAGA);
    }

    @Test
    void codigoComoOAssistenteVe() {
        assertThat(SituacaoFatura.PARCIALMENTE_PAGA.codigo()).isEqualTo("parcialmente_paga");
    }
}
