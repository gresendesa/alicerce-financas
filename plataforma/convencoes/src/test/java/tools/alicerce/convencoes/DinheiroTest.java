package tools.alicerce.convencoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DinheiroTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void leDecimalComPontoESempreTemEscalaDois() {
        assertThat(Dinheiro.ler("1234.5", BRL, "valor").texto()).isEqualTo("1234.50");
        assertThat(Dinheiro.ler("7", BRL, "valor").valor().scale()).isEqualTo(2);
        assertThat(Dinheiro.ler("-0.01", BRL, "valor").centavos()).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234,56", "1.234,56", "1,234.56", "1e3", "12.345", "", " 10.00", "R$ 10", ".5", "10."})
    void recusaFormatosQueEnganamAEscala(String texto) {
        assertThatThrownBy(() -> Dinheiro.ler(texto, BRL, "valor"))
                .isInstanceOfSatisfying(ErroTipado.class, e -> {
                    assertThat(e.codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
                    assertThat(e.campo()).isEqualTo("valor");
                });
    }

    @Test
    void centavosVaoEVoltamSemPerda() {
        Dinheiro d = Dinheiro.deCentavos(123456, BRL);
        assertThat(d.texto()).isEqualTo("1234.56");
        assertThat(d.centavos()).isEqualTo(123456);
        assertThat(Dinheiro.deCentavos(Long.MAX_VALUE, BRL).centavos()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void recusaMaisDeDuasCasasEValorQueNaoCabeEmCentavos() {
        assertThatThrownBy(() -> new Dinheiro(new BigDecimal("0.001"), BRL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Dinheiro(new BigDecimal("1e20"), BRL)).isInstanceOf(ArithmeticException.class);
        assertThat(new Dinheiro(new BigDecimal("1.500"), BRL).texto()).isEqualTo("1.50");
    }

    @Test
    void contasSoNaMesmaMoeda() {
        Dinheiro dez = Dinheiro.ler("10.00", BRL, "valor");
        assertThat(dez.somar(dez).subtrair(Dinheiro.ler("0.01", BRL, "valor")).texto()).isEqualTo("19.99");
        assertThat(dez.negar().ehNegativo()).isTrue();
        assertThat(dez.subtrair(dez).ehZero()).isTrue();
        assertThatThrownBy(() -> dez.somar(Dinheiro.zero(USD))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void igualdadeIndependeDaEscalaDeEntrada() {
        assertThat(new Dinheiro(new BigDecimal("5"), BRL)).isEqualTo(new Dinheiro(new BigDecimal("5.00"), BRL));
    }
}
