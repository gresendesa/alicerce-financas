package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CalculoRitmo.Gasto;
import tools.alicerce.financeiro.dominio.CalculoRitmo.Recorte;
import tools.alicerce.financeiro.dominio.CalculoRitmo.Resultado;
import tools.alicerce.financeiro.dominio.CalculoRitmo.TendenciaCategoria;

class CalculoRitmoTest {

    private static final UUID MERCADO = UUID.randomUUID();
    private static final UUID LAZER = UUID.randomUUID();

    private static Gasto gasto(String data, UUID categoria, long centavos) {
        return gasto(data, categoria, null, centavos, false);
    }

    private static Gasto gasto(String data, UUID categoria, String contraparte, long centavos, boolean fixo) {
        return new Gasto(UUID.randomUUID(), "t-" + centavos, LocalDate.parse(data), categoria,
                contraparte == null ? null : contraparte.toLowerCase(), contraparte, "Compra", centavos, fixo);
    }

    /** Três meses de referência iguais: 100,00 no dia 5 e 200,00 no dia 20 de cada um. */
    private static Map<YearMonth, List<Gasto>> tresMesesIguais() {
        Map<YearMonth, List<Gasto>> ref = new LinkedHashMap<>();
        for (String mes : List.of("2026-08", "2026-09", "2026-10")) {
            ref.put(YearMonth.parse(mes), List.of(gasto(mes + "-05", MERCADO, "Padaria", 10000, false),
                    gasto(mes + "-20", MERCADO, "Padaria", 20000, false)));
        }
        return ref;
    }

    // recorte e meses válidos

    @Test
    void recorteDoMesEmCursoParaHojeEODeMesFechadoNoUltimoDia() {
        LocalDate hoje = LocalDate.parse("2026-11-10");

        assertThat(Recorte.de(YearMonth.parse("2026-11"), hoje)).isEqualTo(new Recorte(YearMonth.parse("2026-11"), 10, true));
        assertThat(Recorte.de(YearMonth.parse("2026-09"), hoje)).isEqualTo(new Recorte(YearMonth.parse("2026-09"), 30, false));
        assertThatThrownBy(() -> Recorte.de(YearMonth.parse("2026-12"), hoje)).isInstanceOf(ErroTipado.class);
    }

    @Test
    void dia31ComparadoComFevereiroParaNoUltimoDiaDele() {
        Recorte recorte = new Recorte(YearMonth.parse("2027-03"), 31, true);

        assertThat(recorte.corteEm(YearMonth.parse("2027-02"))).isEqualTo(LocalDate.parse("2027-02-28"));
        assertThat(recorte.corteEm(YearMonth.parse("2028-02"))).isEqualTo(LocalDate.parse("2028-02-29"));
        assertThat(recorte.corteEm(YearMonth.parse("2027-01"))).isEqualTo(LocalDate.parse("2027-01-31"));
    }

    @Test
    void soValeMesQueComecaDepoisDoMarcoMaisRecente() {
        YearMonth novembro = YearMonth.parse("2026-11");

        assertThat(CalculoRitmo.mesesValidos(novembro, 6, LocalDate.parse("2026-08-01")))
                .containsExactly(YearMonth.parse("2026-08"), YearMonth.parse("2026-09"), YearMonth.parse("2026-10"));
        assertThat(CalculoRitmo.mesesValidos(novembro, 6, LocalDate.parse("2026-08-02")))
                .containsExactly(YearMonth.parse("2026-09"), YearMonth.parse("2026-10"));
        assertThat(CalculoRitmo.mesesValidos(novembro, 3, null))
                .containsExactly(YearMonth.parse("2026-08"), YearMonth.parse("2026-09"), YearMonth.parse("2026-10"));
        assertThat(CalculoRitmo.completoDesde(LocalDate.parse("2026-11-01"))).isEqualTo(YearMonth.parse("2026-11"));
        assertThat(CalculoRitmo.completoDesde(LocalDate.parse("2026-10-26"))).isEqualTo(YearMonth.parse("2026-11"));
    }

    // mediana e percentil

    @Test
    void medianaDeQuantidadeParArredondaAoCentavo() {
        assertThat(CalculoRitmo.mediana(List.of(300L, 100L, 200L))).isEqualTo(200);
        assertThat(CalculoRitmo.mediana(List.of(100L, 200L, 300L, 400L))).isEqualTo(250);
        assertThat(CalculoRitmo.mediana(List.of(100L, 201L))).isEqualTo(150);
        assertThat(CalculoRitmo.mediana(List.of(100L, 203L))).isEqualTo(152);
    }

    @Test
    void percentilSoComVinteComprasPeloPostoMaisProximo() {
        List<Long> dezenove = new ArrayList<>();
        for (long i = 1; i <= 19; i++) {
            dezenove.add(i);
        }
        assertThat(CalculoRitmo.percentil(dezenove)).isNull();

        List<Long> vinte = new ArrayList<>(dezenove);
        vinte.add(20L);
        assertThat(CalculoRitmo.percentil(vinte)).isEqualTo(19);
    }

    // cálculo

    @Test
    void comMenosDeTresMesesValidosNaoHaTipicoNemResponsaveis() {
        Map<YearMonth, List<Gasto>> ref = new LinkedHashMap<>(tresMesesIguais());
        ref.remove(YearMonth.parse("2026-08"));
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);

        Resultado r = CalculoRitmo.calcular(recorte, List.of(gasto("2026-11-03", MERCADO, 15000)), ref, false);

        assertThat(r.acumulado()).isEqualTo(15000);
        assertThat(r.ritmoDiario()).isEqualTo(1500);
        assertThat(r.mantidoRitmoAtual()).isEqualTo(45000);
        assertThat(r.tipico()).isNull();
        assertThat(r.acumuladoMaisRestanteTipico()).isNull();
        assertThat(r.categorias()).isEmpty();
        assertThat(r.comprasAtipicas()).isEmpty();
        assertThat(r.mesesReferencia()).containsExactly(new CalculoRitmo.MesReferencia(YearMonth.parse("2026-09"), 10000, 30000),
                new CalculoRitmo.MesReferencia(YearMonth.parse("2026-10"), 10000, 30000));
    }

    @Test
    void tipicoEhAMedianaAteOMesmoDiaEAsDuasProjecoes() {
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);
        // Uma compra grande no começo do mês: o ritmo atual exagera, o restante típico não.
        List<Gasto> novembro = List.of(gasto("2026-11-02", MERCADO, 60000), gasto("2026-11-11", MERCADO, 99999));

        Resultado r = CalculoRitmo.calcular(recorte, novembro, tresMesesIguais(), false);

        assertThat(r.acumulado()).isEqualTo(60000);
        assertThat(r.tipico().acumulado()).isEqualTo(10000);
        assertThat(r.tipico().ritmoDiario()).isEqualTo(1000);
        assertThat(r.tipico().restante()).isEqualTo(20000);
        assertThat(r.tipico().diferenca()).isEqualTo(50000);
        assertThat(r.tipico().percentual()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(r.mantidoRitmoAtual()).isEqualTo(180000);
        assertThat(r.acumuladoMaisRestanteTipico()).isEqualTo(80000);
    }

    @Test
    void projecaoPeloRitmoNaoArredondaODiarioAntes() {
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 3, true);

        Resultado r = CalculoRitmo.calcular(recorte, List.of(gasto("2026-11-01", MERCADO, 1000)), Map.of(), false);

        assertThat(r.ritmoDiario()).isEqualTo(333);
        assertThat(r.mantidoRitmoAtual()).isEqualTo(10000);
    }

    @Test
    void mesFechadoComparaOMesInteiroESemProjecoes() {
        Recorte recorte = Recorte.de(YearMonth.parse("2026-11"), LocalDate.parse("2026-12-02"));

        Resultado r = CalculoRitmo.calcular(recorte, List.of(gasto("2026-11-30", MERCADO, 40000)), tresMesesIguais(), false);

        assertThat(r.acumulado()).isEqualTo(40000);
        assertThat(r.tipico().acumulado()).isEqualTo(30000);
        assertThat(r.tipico().restante()).isZero();
        assertThat(r.mantidoRitmoAtual()).isNull();
        assertThat(r.acumuladoMaisRestanteTipico()).isNull();
    }

    @Test
    void mesDeReferenciaMaisCurtoContaAteOFimDeleNoDia31() {
        Map<YearMonth, List<Gasto>> ref = new LinkedHashMap<>();
        ref.put(YearMonth.parse("2026-12"), List.of(gasto("2026-12-31", MERCADO, 5000)));
        ref.put(YearMonth.parse("2027-01"), List.of(gasto("2027-01-31", MERCADO, 5000)));
        ref.put(YearMonth.parse("2027-02"), List.of(gasto("2027-02-28", MERCADO, 5000)));
        Recorte recorte = new Recorte(YearMonth.parse("2027-03"), 31, true);

        Resultado r = CalculoRitmo.calcular(recorte, List.of(), ref, false);

        assertThat(r.tipico().acumulado()).isEqualTo(5000);
        assertThat(r.tipico().restante()).isZero();
    }

    @Test
    void fixosFicamForaSalvoComIncluirFixos() {
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);
        List<Gasto> novembro = List.of(gasto("2026-11-05", MERCADO, null, 10000, false), gasto("2026-11-05", LAZER, null, 150000, true));

        assertThat(CalculoRitmo.calcular(recorte, novembro, tresMesesIguais(), false).acumulado()).isEqualTo(10000);
        assertThat(CalculoRitmo.calcular(recorte, novembro, tresMesesIguais(), true).acumulado()).isEqualTo(160000);
    }

    @Test
    void responsaveisPuxamParaOMesmoLadoDaDiferencaDoMaiorParaOMenor() {
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);
        List<Gasto> novembro = List.of(gasto("2026-11-03", MERCADO, "Padaria", 2000, false),
                gasto("2026-11-04", LAZER, "Cinema", 30000, false), gasto("2026-11-06", LAZER, "Bar", 15000, false));

        Resultado r = CalculoRitmo.calcular(recorte, novembro, tresMesesIguais(), false);

        assertThat(r.tipico().diferenca()).isEqualTo(37000);
        // Mercado gastou menos que o típico: puxa para o outro lado e fica fora.
        assertThat(r.categorias()).extracting(CalculoRitmo.Contribuicao::chave).containsExactly(LAZER.toString());
        assertThat(r.categorias().get(0).contribuicao()).isEqualTo(45000);
        assertThat(r.contrapartes()).extracting(CalculoRitmo.Contribuicao::nome).containsExactly("Cinema", "Bar");
        assertThat(r.contrapartes().get(0).tipico()).isZero();
    }

    @Test
    void semDiferencaNaoHaResponsaveis() {
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);

        Resultado r = CalculoRitmo.calcular(recorte, List.of(gasto("2026-11-05", MERCADO, 10000)), tresMesesIguais(), false);

        assertThat(r.tipico().diferenca()).isZero();
        assertThat(r.categorias()).isEmpty();
        assertThat(r.contrapartes()).isEmpty();
    }

    @Test
    void compraAtipicaPassaDoPercentil95DaCategoriaComVinteCompras() {
        Map<YearMonth, List<Gasto>> ref = new LinkedHashMap<>();
        for (String mes : List.of("2026-08", "2026-09", "2026-10")) {
            List<Gasto> gastos = new ArrayList<>();
            for (int dia = 1; dia <= 7; dia++) {
                gastos.add(gasto(mes + "-%02d".formatted(dia), MERCADO, 5000 + dia));
                gastos.add(gasto(mes + "-%02d".formatted(dia), LAZER, 5000 + dia));
            }
            ref.put(YearMonth.parse(mes), gastos);
        }
        // 21 compras em cada categoria; lazer perde uma e fica com 20 no histórico.
        ref.put(YearMonth.parse("2026-10"), ref.get(YearMonth.parse("2026-10")).subList(0, 13));
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);
        Gasto grande = gasto("2026-11-04", MERCADO, 50000);
        List<Gasto> novembro = List.of(grande, gasto("2026-11-05", MERCADO, 5001), gasto("2026-11-06", LAZER, 90000));

        Resultado r = CalculoRitmo.calcular(recorte, novembro, ref, false);

        assertThat(r.comprasAtipicas()).extracting(CalculoRitmo.CompraAtipica::lancamento)
                .containsExactly(novembro.get(2).lancamento(), grande.lancamento());
        assertThat(r.comprasAtipicas().get(1).percentil()).isEqualTo(5007);
        assertThat(r.comprasAtipicas().get(1).excesso()).isEqualTo(50000 - 5007);
    }

    @Test
    void categoriaComMenosDeVinteComprasNaoTemCompraAtipica() {
        Recorte recorte = new Recorte(YearMonth.parse("2026-11"), 10, true);

        Resultado r = CalculoRitmo.calcular(recorte, List.of(gasto("2026-11-02", MERCADO, 999999)), tresMesesIguais(), false);

        assertThat(r.comprasAtipicas()).isEmpty();
    }

    @Test
    void partesDoMesmoLancamentoNaMesmaCategoriaSaoUmaCompra() {
        Map<YearMonth, List<Gasto>> ref = new LinkedHashMap<>();
        for (String mes : List.of("2026-08", "2026-09", "2026-10")) {
            List<Gasto> gastos = new ArrayList<>();
            for (int dia = 1; dia <= 7; dia++) {
                gastos.add(gasto(mes + "-%02d".formatted(dia), MERCADO, 1000));
            }
            ref.put(YearMonth.parse(mes), gastos);
        }
        UUID lancamento = UUID.randomUUID();
        List<Gasto> novembro = List.of(new Gasto(lancamento, "t-X", LocalDate.parse("2026-11-02"), MERCADO, null, null, "Feira", 600, false),
                new Gasto(lancamento, "t-X", LocalDate.parse("2026-11-02"), MERCADO, null, null, "Feira", 600, false));

        Resultado r = CalculoRitmo.calcular(new Recorte(YearMonth.parse("2026-11"), 10, true), novembro, ref, false);

        assertThat(r.comprasAtipicas()).singleElement().satisfies(c -> assertThat(c.valor()).isEqualTo(1200));
    }

    // tendência

    @Test
    void tendenciaUsaARegraDeCategoriaSubindoEVariacaoNulaSobreZero() {
        Map<UUID, List<Long>> saidas = new LinkedHashMap<>();
        saidas.put(MERCADO, List.of(10000L, 12000L, 15000L));
        saidas.put(LAZER, List.of(0L, 5000L, 4000L));

        List<TendenciaCategoria> linhas = CalculoRitmo.tendencia(saidas);

        assertThat(linhas.get(0).categoria()).isEqualTo(MERCADO);
        assertThat(linhas.get(0).subindo()).isTrue();
        assertThat(linhas.get(0).variacoes()).containsExactly(new BigDecimal("20.00"), new BigDecimal("25.00"));
        assertThat(linhas.get(1).subindo()).isFalse();
        assertThat(linhas.get(1).variacoes()).containsExactly(null, new BigDecimal("-20.00"));
    }
}
