package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.financeiro.dominio.CompraNoCredito.Lado;

class CalculoCaixaTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private final UUID cartao = UUID.randomUUID();
    private final UUID mercado = UUID.randomUUID();
    private final UUID lazer = UUID.randomUUID();
    private final UUID eu = UUID.randomUUID();
    private final UUID mae = UUID.randomUUID();

    private static Dinheiro reais(long centavos) {
        return Dinheiro.deCentavos(centavos, BRL);
    }

    private Lado parcela(int numero, long centavos, UUID ambito, String vencimento) {
        return new Lado(cartao, ambito, reais(centavos), numero, LocalDate.parse(vencimento));
    }

    private CompraNoCredito compra() {
        return new CompraNoCredito(UUID.randomUUID(), "t-AAAAAA", "TV", null, null,
                List.of(new Lado(mercado, eu, reais(6000), 0, null), new Lado(lazer, mae, reais(4000), 0, null)),
                List.of(parcela(1, 2001, eu, "2026-09-17"), parcela(1, 1333, mae, "2026-09-17"),
                        parcela(2, 2000, eu, "2026-10-17"), parcela(2, 1333, mae, "2026-10-17"),
                        parcela(3, 1999, eu, "2026-11-17"), parcela(3, 1334, mae, "2026-11-17")));
    }

    private static Periodo mes(String texto) {
        return Periodo.ler(texto, "periodo");
    }

    @Test
    void cadaParcelaContaNoMesDoVencimento() {
        CalculoResumo.Totais setembro = CalculoResumo.totais(CalculoCaixa.fatos(compra(), mes("2026-09"), null), BRL);
        CalculoResumo.Totais agosto = CalculoResumo.totais(CalculoCaixa.fatos(compra(), mes("2026-08"), null), BRL);

        assertThat(setembro.saidas()).isEqualTo(reais(3334));
        assertThat(setembro.entradas()).isEqualTo(reais(0));
        assertThat(agosto.saidas()).isEqualTo(reais(0));
    }

    @Test
    void osMesesSomamACompraExataTambemPorCategoria() {
        CompraNoCredito compra = compra();
        List<FatoPeriodo> todos = new ArrayList<>();
        for (String mes : List.of("2026-09", "2026-10", "2026-11")) {
            todos.addAll(CalculoCaixa.fatos(compra, mes(mes), null));
        }

        assertThat(CalculoResumo.totais(todos, BRL).saidas()).isEqualTo(reais(10000));
        assertThat(soma(todos, mercado)).isEqualTo(6000);
        assertThat(soma(todos, lazer)).isEqualTo(4000);
        assertThat(soma(todos, cartao)).isEqualTo(10000);
    }

    @Test
    void filtroDeAmbitoPegaSoOQueEDele() {
        List<FatoPeriodo> fatos = CalculoCaixa.fatos(compra(), mes("2026"), mae);

        assertThat(fatos).allMatch(f -> mae.equals(f.ambito()));
        assertThat(soma(fatos, lazer)).isEqualTo(4000);
        assertThat(soma(fatos, mercado)).isZero();
    }

    @Test
    void aDataDoFatoEOVencimento() {
        assertThat(CalculoCaixa.fatos(compra(), mes("2026-10"), null)).allMatch(f -> f.data().equals(LocalDate.parse("2026-10-17")));
    }

    private static long soma(List<FatoPeriodo> fatos, UUID conta) {
        return fatos.stream().filter(f -> f.conta().equals(conta)).mapToLong(f -> f.absoluto().centavos()).sum();
    }
}
