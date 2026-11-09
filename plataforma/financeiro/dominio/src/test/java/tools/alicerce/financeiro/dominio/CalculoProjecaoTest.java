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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Base;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Categoria;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Coluna;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Fluxo;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Mes;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Resultado;

class CalculoProjecaoTest {

    private static final UUID MERCADO = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LAZER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CARTAO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final LocalDate HOJE = LocalDate.parse("2026-11-10");
    /** Fecha no dia 20 e vence no dia 10 do mês seguinte ao fechamento. */
    private static final CalculoProjecao.Vencimentos DIA_20 = (cartao, compra) -> {
        YearMonth fecha = compra.getDayOfMonth() <= 20 ? YearMonth.from(compra) : YearMonth.from(compra).plusMonths(1);
        return fecha.plusMonths(1).atDay(10);
    };
    /** Seis meses: totais 100..600 (mediana 350, p80 500). */
    private static final List<Long> SEIS = List.of(10000L, 20000L, 30000L, 40000L, 50000L, 60000L);

    private static Base base(int horizonte, long partida, List<Fluxo> fluxos, List<Categoria> categorias, int meses, Long piso) {
        return new Base(HOJE, horizonte, partida, fluxos, categorias, meses, piso, DIA_20);
    }

    private static Mes mes(Resultado r, int i) {
        return r.meses().get(i);
    }

    @Test
    void semHistoricoOsFluxosConhecidosEntramPeloMesEOAtrasadoNoCorrente() {
        Base b = base(3, 100000, List.of(new Fluxo(Coluna.SAIDA, LocalDate.parse("2026-11-05"), 20000),
                new Fluxo(Coluna.ENTRADA, LocalDate.parse("2026-12-05"), 50000), new Fluxo(Coluna.FATURA, LocalDate.parse("2027-01-10"), 30000),
                new Fluxo(Coluna.FATURA, LocalDate.parse("2027-02-10"), 99999)), List.of(), 0, 90000L);
        Resultado r = CalculoProjecao.projetar(b, List.of());

        assertThat(r.meses()).extracting(Mes::mes).containsExactly(YearMonth.parse("2026-11"), YearMonth.parse("2026-12"),
                YearMonth.parse("2027-01"));
        assertThat(mes(r, 0).saidas()).isEqualTo(20000);
        assertThat(mes(r, 0).variavelP50()).isNull();
        assertThat(mes(r, 0).variavelP80()).isNull();
        assertThat(r.meses()).extracting(Mes::finalP50).containsExactly(80000L, 130000L, 100000L);
        assertThat(r.meses()).extracting(Mes::finalP80).containsExactly(80000L, 130000L, 100000L);
        assertThat(mes(r, 1).inicialP50()).isEqualTo(80000);
        assertThat(r.menorP50()).isEqualTo(new CalculoProjecao.Menor(80000, YearMonth.parse("2026-11")));
        assertThat(r.abaixoPisoP80()).containsExactly(YearMonth.parse("2026-11"));
        // Fatura sem entrada no mês não tem comprometimento: não há pico.
        assertThat(r.pico()).isNull();
        assertThat(b.fim()).isEqualTo("2027-01-31");
    }

    @Test
    void oGastoVariavelUsaAMedianaEOPercentil80NoMesCorrenteSoORestante() {
        Base b = base(2, 100000, List.of(), List.of(new Categoria(MERCADO, SEIS, 20000, Map.of())), 6, null);
        Resultado r = CalculoProjecao.projetar(b, List.of());

        assertThat(r.meses()).extracting(Mes::variavelP50).containsExactly(15000L, 35000L);
        assertThat(r.meses()).extracting(Mes::variavelP80).containsExactly(30000L, 50000L);
        assertThat(r.meses()).extracting(Mes::finalP50).containsExactly(85000L, 50000L);
        assertThat(r.meses()).extracting(Mes::finalP80).containsExactly(70000L, 20000L);
        assertThat(r.menorP80()).isEqualTo(new CalculoProjecao.Menor(20000, YearMonth.parse("2026-12")));
        assertThat(r.abaixoPisoP50()).isEmpty();
    }

    @Test
    void jaGastoAlemDoTipicoNaoDeixaRestanteNegativo() {
        Base b = base(1, 0, List.of(), List.of(new Categoria(MERCADO, SEIS, 90000, Map.of())), 6, null);
        assertThat(mes(CalculoProjecao.projetar(b, List.of()), 0).variavelP80()).isZero();
    }

    @Test
    void aParteDoCartaoVaiParaOMesEmQueAFaturaVenceEOQueVencerDepoisDoHorizonteFicaFora() {
        long total = SEIS.stream().mapToLong(Long::longValue).sum();
        Base b = base(3, 0, List.of(), List.of(new Categoria(MERCADO, SEIS, 0, Map.of(CARTAO, total))), 6, null);
        Resultado r = CalculoProjecao.projetar(b, List.of());

        // Novembro: 350,00 em 20 dias (11 a 30); de 11 a 20 vence em dezembro, de 21 a 30 em janeiro.
        // Dezembro: 350,00 em 31 dias (11,30 no primeiro, 11,29 nos outros); de 1 a 20 vence em janeiro, o resto em fevereiro.
        assertThat(r.meses()).extracting(Mes::variavelP50).containsExactly(0L, 17500L, 17500L + 22581L);
        assertThat(mes(r, 2).finalP50()).isEqualTo(-(17500L + 17500L + 22581L));
    }

    @Test
    void soAParteHistoricaDoCartaoSeDesloca() {
        long total = SEIS.stream().mapToLong(Long::longValue).sum();
        Base b = base(1, 0, List.of(), List.of(new Categoria(MERCADO, SEIS, 0, Map.of(CARTAO, total / 4))), 6, null);
        // Um quarto de 350,00 vai para faturas de dezembro e janeiro, fora do horizonte de um mês.
        assertThat(mes(CalculoProjecao.projetar(b, List.of()), 0).variavelP50()).isEqualTo(35000 - 8750);
    }

    @Test
    void hipotesesMudamRendaCategoriaEFluxosEADiferencaEContraALinhaDeBase() {
        Base b = base(2, 100000, List.of(), List.of(new Categoria(MERCADO, SEIS, 20000, Map.of()),
                new Categoria(LAZER, SEIS, 0, Map.of())), 6, null);
        Resultado linha = CalculoProjecao.projetar(b, List.of());
        Resultado cenario = CalculoProjecao.projetar(b, List.of(
                new CalculoProjecao.MudancaCategoria(Set.of(MERCADO), new BigDecimal("-50"), YearMonth.parse("2026-12")),
                new CalculoProjecao.MudancaRenda(10000, YearMonth.parse("2026-12")),
                new CalculoProjecao.Fluxos(List.of(new Fluxo(Coluna.FATURA, LocalDate.parse("2026-12-10"), 5000)))));

        // Mercado: 150,00 + 175,00 (metade de 350,00); Lazer: 350,00 + 350,00.
        assertThat(cenario.meses()).extracting(Mes::variavelP50).containsExactly(15000L + 35000L, 17500L + 35000L);
        assertThat(mes(cenario, 1).entradas()).isEqualTo(10000);
        assertThat(mes(cenario, 1).faturas()).isEqualTo(5000);
        assertThat(mes(cenario, 1).finalP50()).isEqualTo(50000 + 10000 - 5000 - 52500);
        assertThat(cenario.pico()).isEqualTo(new CalculoProjecao.Pico(YearMonth.parse("2026-12"), 5000, 10000, new BigDecimal("50.00")));

        CalculoProjecao.Diferenca d = CalculoProjecao.diferenca(linha, cenario);
        assertThat(d.meses()).extracting(CalculoProjecao.DiferencaMes::finalP50).containsExactly(0L, 22500L);
        assertThat(d.finalP50()).isEqualTo(22500);
        assertThat(d.menorP50()).isEqualTo(mes(cenario, 1).finalP50() - mes(linha, 1).finalP50());
    }

    @Test
    void mudancaDeCategoriaNoMesCorrenteMudaORestante() {
        Base b = base(1, 0, List.of(), List.of(new Categoria(MERCADO, SEIS, 20000, Map.of())), 6, null);
        Resultado r = CalculoProjecao.projetar(b, List.of(new CalculoProjecao.MudancaCategoria(Set.of(MERCADO), new BigDecimal("10"),
                YearMonth.parse("2026-11"))));
        assertThat(mes(r, 0).variavelP50()).isEqualTo(16500);
    }

    @Test
    void diasDoGastoNoUltimoDiaDoMesEPartesIguais() {
        assertThat(CalculoProjecao.dias(LocalDate.parse("2026-11-30"), YearMonth.parse("2026-11"), true)).containsExactly(LocalDate.parse("2026-11-30"));
        assertThat(CalculoProjecao.dias(HOJE, YearMonth.parse("2026-11"), true)).hasSize(20);
        assertThat(CalculoProjecao.dias(HOJE, YearMonth.parse("2027-02"), false)).hasSize(28);
        assertThat(CalculoProjecao.porIgual(10, 3)).containsExactly(4, 3, 3);
    }

    @Test
    void horizonteForaDoLimiteEhRecusado() {
        assertThatThrownBy(() -> base(25, 0, List.of(), List.of(), 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(0, 0, List.of(), List.of(), 0, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void categoriasTiramFixosEAtipicasEContamAParteDeCadaCartao() {
        Map<YearMonth, List<CalculoRitmo.Gasto>> referencia = new LinkedHashMap<>();
        UUID noCartao = UUID.randomUUID();
        for (YearMonth m : List.of(YearMonth.parse("2026-08"), YearMonth.parse("2026-09"), YearMonth.parse("2026-10"))) {
            List<CalculoRitmo.Gasto> gastos = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                UUID lancamento = m.getMonthValue() == 8 && i == 0 ? noCartao : UUID.randomUUID();
                gastos.add(gasto(lancamento, m.atDay(i + 1), 1000, false));
            }
            gastos.add(gasto(UUID.randomUUID(), m.atDay(15), 5000, true));
            referencia.put(m, gastos);
        }
        // Uma compra atípica em outubro: acima do percentil 95 das 22 compras.
        referencia.get(YearMonth.parse("2026-10")).add(gasto(UUID.randomUUID(), LocalDate.parse("2026-10-20"), 100000, false));
        List<CalculoRitmo.Gasto> doMes = List.of(gasto(UUID.randomUUID(), HOJE, 700, false), gasto(UUID.randomUUID(), HOJE.plusDays(1), 900, false),
                gasto(UUID.randomUUID(), HOJE, 300, true));

        List<Categoria> categorias = CalculoProjecao.categorias(HOJE, doMes, referencia, Map.of(noCartao, CARTAO));
        assertThat(categorias).hasSize(1);
        assertThat(categorias.get(0).totais()).containsExactly(7000L, 7000L, 7000L);
        assertThat(categorias.get(0).gastoAteHoje()).isEqualTo(700);
        assertThat(categorias.get(0).noCartao()).containsExactlyEntriesOf(Map.of(CARTAO, 1000L));
    }

    private static CalculoRitmo.Gasto gasto(UUID lancamento, LocalDate data, long centavos, boolean fixo) {
        return new CalculoRitmo.Gasto(lancamento, "t-AAAAAA", data, MERCADO, null, null, "compra", centavos, fixo);
    }
}
