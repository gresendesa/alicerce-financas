package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CalculoConversao.Cotacao;
import tools.alicerce.financeiro.dominio.CalculoConversao.SaldoConta;

class CalculoConversaoTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DATA = LocalDate.of(2026, 10, 30);

    private static SaldoConta saldo(String valor, Currency moeda) {
        return new SaldoConta(UUID.randomUUID(), Dinheiro.ler(valor, moeda, "saldo"));
    }

    private static Map<Currency, Cotacao> cotacao(Currency moeda, String valor) {
        return Map.of(moeda, new Cotacao(new BigDecimal(valor), DATA, "t-1"));
    }

    @Test
    void converteCadaMoedaPelaSuaCotacaoESomaNaMoedaDoDono() {
        var r = CalculoConversao.converter(List.of(saldo("100.00", BRL), saldo("10.00", USD)), cotacao(USD, "5.525"), BRL);

        assertThat(r.naMoedaDoDono().texto()).isEqualTo("100.00");
        assertThat(r.convertido().texto()).isEqualTo("55.25");
        assertThat(r.total().texto()).isEqualTo("155.25");
        assertThat(r.linhas()).hasSize(1);
        assertThat(r.foraDoTotal()).isEmpty();
    }

    @Test
    void arredondaMeioCentavoParaOPar() {
        // 0,50 × 5,53 = 2,765 e 0,50 × 5,51 = 2,755: HALF_EVEN, para o par nos dois sentidos.
        assertThat(CalculoConversao.converter(List.of(saldo("0.50", USD)), cotacao(USD, "5.53"), BRL).convertido().texto()).isEqualTo("2.76");
        assertThat(CalculoConversao.converter(List.of(saldo("0.50", USD)), cotacao(USD, "5.51"), BRL).convertido().texto()).isEqualTo("2.76");
    }

    @Test
    void semCotacaoOSaldoFicaForaDoTotalESemSomarMoedas() {
        var r = CalculoConversao.converter(List.of(saldo("100.00", BRL), saldo("10.00", USD), saldo("7.00", EUR)), cotacao(USD, "5.00"), BRL);

        assertThat(r.total().texto()).isEqualTo("150.00");
        assertThat(r.foraDoTotal()).hasSize(1);
        assertThat(r.foraDoTotal().get(0).saldo().moeda()).isEqualTo(EUR);
        assertThat(r.foraDoTotal().get(0).convertido()).isNull();
    }

    @Test
    void saldoZeroSemCotacaoNaoEFaltaNenhuma() {
        var r = CalculoConversao.converter(List.of(saldo("0.00", USD)), Map.of(), BRL);

        assertThat(r.foraDoTotal()).isEmpty();
        assertThat(r.linhas()).hasSize(1);
        assertThat(r.total().ehZero()).isTrue();
    }

    @Test
    void lancamentoMisturandoMoedasSemConversaoNaoNasce() {
        List<Movimento> mistura = List.of(new Movimento(UUID.randomUUID(), Dinheiro.ler("-5.00", BRL, "v"), null),
                new Movimento(UUID.randomUUID(), Dinheiro.ler("5.00", USD, "v"), null));

        assertThatThrownBy(() -> new Lancamento(UUID.randomUUID(), TipoLancamento.TRANSFERENCIA, DATA, "x", null, null, null, OrigemLancamento.CONVERSA, null, mistura, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("soma zero");
    }

    @Test
    void cambioSomaZeroEmCadaMoedaEOTotalEOLadoDoDono() {
        UUID origem = UUID.randomUUID();
        UUID destino = UUID.randomUUID();
        List<Movimento> movimentos = Partidas.cambio(Dinheiro.ler("5500.00", BRL, "v"), origem, UUID.randomUUID(), Dinheiro.ler("1000.00", USD, "v"), UUID.randomUUID(), destino);
        ValorOriginal original = new ValorOriginal(new BigDecimal("1000.00"), USD, new BigDecimal("5.5"), DATA);

        Lancamento cambio = new Lancamento(UUID.randomUUID(), TipoLancamento.TRANSFERENCIA, DATA, "x", null, null, null, OrigemLancamento.CONVERSA, null, movimentos, List.of())
                .comOriginal(original);

        assertThat(cambio.ehCambio()).isTrue();
        assertThat(cambio.total()).isEqualTo(Dinheiro.ler("5500.00", BRL, "v"));
        Lancamento estorno = cambio.estorno(UUID.randomUUID(), "erro");
        assertThat(estorno.total()).isEqualTo(Dinheiro.ler("5500.00", BRL, "v"));
        assertThat(estorno.movimentos()).extracting(m -> m.valor().moeda()).containsExactly(BRL, BRL, USD, USD);

        List<Movimento> refeitos = Partidas.comCambio(movimentos, Dinheiro.ler("6000.00", BRL, "v"), Dinheiro.ler("1000.00", USD, "v"));
        assertThat(refeitos.get(0).valor().texto()).isEqualTo("-6000.00");
        assertThat(refeitos.get(3).valor().texto()).isEqualTo("1000.00");
    }

    @Test
    void moedaDeContaSoComDuasCasasEDoDonoParaEstrangeira() {
        assertThat(ValorOriginal.lerMoedaDeConta(" usd ", "moeda")).isEqualTo(USD);
        assertThat(catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerMoedaDeConta("JPY", "moeda")).mensagem()).contains("0 casas");
        assertThat(catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerMoedaDeConta("BHD", "moeda")).mensagem()).contains("3 casas");
        assertThat(catchThrowableOfType(ErroTipado.class, () -> ValorOriginal.lerMoedaDeConta("XAU", "moeda")).mensagem()).contains("não é uma moeda de pagamento");

        var inversa = ValorOriginal.daMoedaDoDono(Dinheiro.ler("100.00", BRL, "v"), USD, new BigDecimal("3"), DATA, "");
        assertThat(inversa.original().valor()).isEqualByComparingTo("33.33");
        assertThat(inversa.valor().texto()).isEqualTo("100.00");
    }
}
