package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.financeiro.dominio.CalculoCortes.Categoria;
import tools.alicerce.financeiro.dominio.CalculoCortes.Entrada;
import tools.alicerce.financeiro.dominio.CalculoCortes.Estimativa;
import tools.alicerce.financeiro.dominio.CalculoCortes.LinhaCategoria;
import tools.alicerce.financeiro.dominio.CalculoCortes.Resultado;

class CalculoCortesTest {

    private static final UUID MERCADO = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LAZER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DELIVERY = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /** Seis meses: totais 100..600 (p80 = 500, mediana = 350); restantes 50..300 (p25 = 100). */
    private static Categoria categoria(UUID id, long gasto, boolean essencial) {
        return new Categoria(id, gasto, List.of(10000L, 20000L, 30000L, 40000L, 50000L, 60000L),
                List.of(5000L, 10000L, 15000L, 20000L, 25000L, 30000L), essencial);
    }

    private static LinhaCategoria linha(Resultado r, UUID id) {
        return r.categorias().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void fimDoPeriodoEOUltimoDiaDoMes() {
        assertThat(CalculoCortes.fimDoPeriodo(LocalDate.parse("2026-02-10"))).isEqualTo("2026-02-28");
        assertThat(CalculoCortes.fimDoPeriodo(LocalDate.parse("2026-11-30"))).isEqualTo("2026-11-30");
    }

    @Test
    void percentilPeloPostoMaisProximo() {
        List<Long> seis = List.of(60L, 10L, 30L, 20L, 50L, 40L);
        assertThat(CalculoCortes.percentil(seis, 80)).isEqualTo(50);
        assertThat(CalculoCortes.percentil(seis, 25)).isEqualTo(20);
        assertThat(CalculoCortes.percentil(List.of(7L, 3L, 5L), 80)).isEqualTo(7);
        assertThat(CalculoCortes.percentil(List.of(7L, 3L, 5L), 25)).isEqualTo(3);
        assertThat(CalculoCortes.percentil(List.of(9L), 25)).isEqualTo(9);
    }

    @Test
    void semDeficitSaiFolgaESemPlano() {
        Resultado r = CalculoCortes.calcular(new Entrada(500000, 100000, 200000, List.of(categoria(MERCADO, 20000, false)), 6, 1,
                Estimativa.P80));
        // restante = 50000 − 20000 = 30000; saldo = 500000 + 100000 − 200000 − 30000.
        assertThat(r.variavelRestante()).isEqualTo(30000);
        assertThat(r.saldoProjetado()).isEqualTo(370000);
        assertThat(r.distancia()).isEqualTo(369999);
        assertThat(r.deficit()).isZero();
        assertThat(r.coberto()).isZero();
        assertThat(r.descoberto()).isZero();
        assertThat(r.plano()).isEmpty();
        LinhaCategoria m = linha(r, MERCADO);
        assertThat(m.tipico()).isEqualTo(50000);
        assertThat(m.piso()).isEqualTo(10000);
        assertThat(m.espaco()).isEqualTo(20000);
    }

    @Test
    void estimativaOtimistaUsaAMediana() {
        Resultado r = CalculoCortes.calcular(new Entrada(0, 0, 0, List.of(categoria(MERCADO, 0, false)), 6, 0, Estimativa.P50));
        assertThat(linha(r, MERCADO).tipico()).isEqualTo(35000);
        assertThat(r.saldoProjetado()).isEqualTo(-35000);
    }

    @Test
    void restanteNuncaNegativoQuandoJaPassouDoTipico() {
        Resultado r = CalculoCortes.calcular(new Entrada(100000, 0, 0, List.of(categoria(MERCADO, 90000, false)), 6, 0, Estimativa.P80));
        LinhaCategoria m = linha(r, MERCADO);
        assertThat(m.restante()).isZero();
        assertThat(m.espaco()).isZero();
        assertThat(r.saldoProjetado()).isEqualTo(100000);
    }

    @Test
    void deficitCobertoProporcionalAoEspacoSemTocarAsEssenciais() {
        // Lazer: espaço 20000 (restante 30000 − piso 10000). Delivery: gasto 0, restante 50000, espaço 40000.
        Resultado r = CalculoCortes.calcular(new Entrada(0, 0, 0, List.of(categoria(MERCADO, 0, true), categoria(LAZER, 20000, false),
                categoria(DELIVERY, 0, false)), 6, -160000, Estimativa.P80));
        // variável = 50000 + 30000 + 50000; saldo = −130000; alvo −160000: folga. Agora com alvo que dá déficit de 30001.
        assertThat(r.distancia()).isEqualTo(30000);
        r = CalculoCortes.calcular(new Entrada(0, 0, 0, List.of(categoria(MERCADO, 0, true), categoria(LAZER, 20000, false),
                categoria(DELIVERY, 0, false)), 6, -99999, Estimativa.P80));
        assertThat(r.deficit()).isEqualTo(30001);
        assertThat(linha(r, LAZER).corte() + linha(r, DELIVERY).corte()).isEqualTo(30001);
        assertThat(linha(r, LAZER).corte()).isEqualTo(10000);
        assertThat(linha(r, DELIVERY).corte()).isEqualTo(20001);
        assertThat(linha(r, MERCADO).corte()).isZero();
        assertThat(r.coberto()).isEqualTo(30001);
        assertThat(r.descoberto()).isZero();
        assertThat(r.precisaEssenciais()).isFalse();
        assertThat(r.plano()).extracting(LinhaCategoria::id).containsExactly(DELIVERY, LAZER);
        // Ordenadas pelo espaço: Mercado e Delivery (40000) antes de Lazer.
        assertThat(r.categorias()).extracting(LinhaCategoria::id).last().isEqualTo(LAZER);
    }

    @Test
    void soCategoriasEssenciaisComEspacoEntramNoPlanoDepoisDasOutras() {
        Resultado r = CalculoCortes.calcular(new Entrada(0, 0, 0, List.of(categoria(MERCADO, 0, true), categoria(LAZER, 20000, false)), 6,
                -50000, Estimativa.P80));
        // variável 80000; saldo −80000; déficit 30000: Lazer dá 20000, Mercado o resto.
        assertThat(r.deficit()).isEqualTo(30000);
        assertThat(linha(r, LAZER).corte()).isEqualTo(20000);
        assertThat(linha(r, MERCADO).corte()).isEqualTo(10000);
        assertThat(r.precisaEssenciais()).isTrue();
        assertThat(r.plano()).extracting(LinhaCategoria::id).containsExactly(LAZER, MERCADO);

        Resultado soEssenciais = CalculoCortes.calcular(new Entrada(0, 0, 0, List.of(categoria(MERCADO, 0, true)), 6, -45000,
                Estimativa.P80));
        assertThat(linha(soEssenciais, MERCADO).corte()).isEqualTo(5000);
        assertThat(soEssenciais.precisaEssenciais()).isTrue();
    }

    @Test
    void deficitMaiorQueTodoOEspacoCortaTudoEDeixaODescoberto() {
        Resultado r = CalculoCortes.calcular(new Entrada(0, 0, 500000, List.of(categoria(MERCADO, 0, true), categoria(LAZER, 20000, false)), 6,
                1, Estimativa.P80));
        assertThat(r.espacoTotal()).isEqualTo(60000);
        assertThat(r.deficit()).isEqualTo(580001);
        assertThat(r.coberto()).isEqualTo(60000);
        assertThat(r.descoberto()).isEqualTo(520001);
        assertThat(linha(r, MERCADO).corte()).isEqualTo(40000);
        assertThat(linha(r, LAZER).corte()).isEqualTo(20000);
    }

    @Test
    void comMenosDeTresMesesSaiADistanciaMasNaoSaiPlano() {
        Categoria curta = new Categoria(LAZER, 0, List.of(30000L, 50000L), List.of(10000L, 20000L), false);
        Resultado r = CalculoCortes.calcular(new Entrada(10000, 0, 0, List.of(curta), 2, 1, Estimativa.P80));
        assertThat(r.historicoSuficiente()).isFalse();
        assertThat(r.saldoProjetado()).isEqualTo(-40000);
        assertThat(r.deficit()).isEqualTo(40001);
        assertThat(r.plano()).isEmpty();
        assertThat(r.coberto()).isZero();
        assertThat(r.descoberto()).isZero();

        Resultado vazio = CalculoCortes.calcular(new Entrada(10000, 0, 0, List.of(new Categoria(LAZER, 3000, List.of(), List.of(), false)), 0,
                1, Estimativa.P80));
        assertThat(vazio.variavelRestante()).isZero();
        assertThat(vazio.distancia()).isEqualTo(9999);
    }

    @Test
    void centavosQueSobramVaoParaAsMaioresSobras() {
        // Três categorias de espaço igual e déficit de 10 centavos: 4 + 3 + 3.
        List<Categoria> tres = List.of(categoria(MERCADO, 20000, false), categoria(LAZER, 20000, false), categoria(DELIVERY, 20000, false));
        Resultado r = CalculoCortes.calcular(new Entrada(90000, 0, 0, tres, 6, 10, Estimativa.P80));
        assertThat(r.deficit()).isEqualTo(10);
        assertThat(r.plano()).extracting(LinhaCategoria::corte).containsExactly(4L, 3L, 3L);
    }

    private static CalculoRitmo.Gasto g(UUID categoria, String data, long centavos, boolean fixo) {
        return new CalculoRitmo.Gasto(UUID.randomUUID(), null, LocalDate.parse(data), categoria, null, null, null, centavos, fixo);
    }

    @Test
    void categoriasSemFixosSemAtipicasEComOMesmoTrechoDoMes() {
        LocalDate hoje = LocalDate.parse("2026-03-10");
        java.util.Map<java.time.YearMonth, List<CalculoRitmo.Gasto>> referencia = new java.util.LinkedHashMap<>();
        List<CalculoRitmo.Gasto> fevereiro = new java.util.ArrayList<>();
        // Vinte compras de 10,00 e uma de 1.000,00 (acima do percentil 95: fora); um fixo (fora).
        for (int dia = 1; dia <= 20; dia++) {
            fevereiro.add(g(MERCADO, "2026-02-%02d".formatted(dia), 1000, false));
        }
        fevereiro.add(g(MERCADO, "2026-02-28", 100000, false));
        fevereiro.add(g(LAZER, "2026-02-05", 5000, true));
        referencia.put(java.time.YearMonth.parse("2026-02"), fevereiro);
        referencia.put(java.time.YearMonth.parse("2026-01"), List.of(g(LAZER, "2026-01-31", 3000, false)));
        List<CalculoRitmo.Gasto> doMes = List.of(g(MERCADO, "2026-03-02", 700, false), g(MERCADO, "2026-03-20", 900, false),
                g(DELIVERY, "2026-03-03", 400, true));

        List<Categoria> categorias = CalculoCortes.categorias(hoje, LocalDate.parse("2026-03-31"), doMes, referencia, java.util.Set.of(MERCADO));

        assertThat(categorias).extracting(Categoria::id).containsExactly(MERCADO, LAZER);
        Categoria mercado = categorias.get(0);
        assertThat(mercado.essencial()).isTrue();
        assertThat(mercado.gastoAteHoje()).isEqualTo(700);
        // Janeiro antes de fevereiro; o fevereiro só soma as vinte de 10,00, dez delas depois do dia 10.
        assertThat(mercado.totais()).containsExactly(0L, 20000L);
        assertThat(mercado.restantes()).containsExactly(0L, 10000L);
        Categoria lazer = categorias.get(1);
        assertThat(lazer.totais()).containsExactly(3000L, 0L);
        assertThat(lazer.restantes()).containsExactly(3000L, 0L);

        // Alvo avulso no dia 15: o total vai até o dia 15 de cada mês, e o de janeiro (dia 31) fica fora.
        List<Categoria> ate15 = CalculoCortes.categorias(hoje, LocalDate.parse("2026-03-15"), doMes, referencia, java.util.Set.of());
        assertThat(ate15.get(0).totais()).containsExactly(0L, 15000L);
        assertThat(ate15.get(0).restantes()).containsExactly(0L, 5000L);
        assertThat(ate15).extracting(Categoria::id).containsExactly(MERCADO);
    }
}
