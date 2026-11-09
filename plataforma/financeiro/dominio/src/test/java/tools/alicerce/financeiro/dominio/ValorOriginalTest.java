package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.ValorOriginal.Conversao;

class ValorOriginalTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");
    private static final LocalDate DATA = LocalDate.of(2026, 10, 30);

    @Test
    void valorInformadoGuardaACotacaoImplicita() {
        Conversao c = converter("20.00", USD, "110.40", null);

        assertThat(c.valor().texto()).isEqualTo("110.40");
        assertThat(c.original().cotacaoTexto()).isEqualTo("5.52");
        assertThat(c.original().unidadesMinimas()).isEqualTo(2000);
    }

    @Test
    void cotacaoInformadaCalculaOValorComHalfEven() {
        assertThat(converter("10.25", USD, null, "1.00").valor().texto()).isEqualTo("10.25");
        // 0,125 e 0,135 caem na metade: vão para o par.
        assertThat(converter("1.25", USD, null, "0.1").valor().texto()).isEqualTo("0.12");
        assertThat(converter("1.35", USD, null, "0.1").valor().texto()).isEqualTo("0.14");
    }

    @Test
    void osTresValoresToleramUmCentavoEValeOInformado() {
        Conversao c = converter("20.00", USD, "110.41", "5.52");

        assertThat(c.valor().texto()).isEqualTo("110.41");
        assertThat(c.original().cotacaoTexto()).isEqualTo("5.52");
    }

    @Test
    void osTresValoresIncoerentesSaoRecusados() {
        ErroTipado erro = catchThrowableOfType(ErroTipado.class, () -> converter("20.00", USD, "110.42", "5.52"));

        assertThat(erro.codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(erro.mensagem()).contains("110.40", "110.42");
    }

    @Test
    void valorSegueOExpoenteIso4217DaMoeda() {
        Currency iene = Currency.getInstance("JPY");
        Currency dinar = Currency.getInstance("KWD");

        assertThat(ValorOriginal.deUnidadesMinimas(1500, iene, BigDecimal.ONE, DATA).valor().toPlainString()).isEqualTo("1500");
        assertThat(converter("1500", iene, "55.00", null).original().unidadesMinimas()).isEqualTo(1500);
        assertThat(converter("1.234", dinar, "20.00", null).original().unidadesMinimas()).isEqualTo(1234);
        assertThat(ValorOriginal.lerValor("1.234", dinar, "valor_original")).isEqualByComparingTo("1.234");
        assertThat(catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerValor("15.5", iene, "valor_original")).campo())
                .isEqualTo("valor_original");
        assertThat(catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerValor("1.234", USD, "valor_original")).mensagem())
                .contains("2 casas");
    }

    @Test
    void moedaInexistenteMetalEAMoedaDaContaSaoRecusados() {
        assertThat(ValorOriginal.lerMoeda(" usd ", BRL, "moeda_original")).isEqualTo(USD);
        for (String codigo : new String[] {"ZZZ", "XAU", "BRL", "dolar"}) {
            ErroTipado erro = catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerMoeda(codigo, BRL, "moeda_original"));
            assertThat(erro.codigo()).as(codigo).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
            assertThat(erro.campo()).isEqualTo("moeda_original");
        }
    }

    @Test
    void cotacaoInvalidaOuConversaoSemValorSaoRecusadas() {
        for (String cotacao : new String[] {"0", "-1", "5,52", "1e3", "1.00000000001"}) {
            assertThat(catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerCotacao(cotacao, "cotacao")).codigo())
                    .as(cotacao).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        }
        assertThat(catchThrowableOfType(ErroTipado.class, () -> converter("0.01", USD, null, "0.1")).mensagem()).contains("zero");
    }

    private static Conversao converter(String valor, Currency moeda, String informado, String cotacao) {
        return ValorOriginal.converter(new BigDecimal(valor), moeda, informado == null ? null : Dinheiro.ler(informado, BRL, "valor"),
                cotacao == null ? null : new BigDecimal(cotacao), DATA, BRL, "");
    }
}
