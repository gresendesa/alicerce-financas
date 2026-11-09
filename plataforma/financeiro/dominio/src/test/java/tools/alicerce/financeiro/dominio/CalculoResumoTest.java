package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CalculoResumo.Grupo;
import tools.alicerce.financeiro.dominio.CalculoResumo.LinhaQuebra;
import tools.alicerce.financeiro.dominio.CalculoResumo.Totais;

class CalculoResumoTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 10);

    @Test
    void totaisContamCadaLancamentoUmaVez() {
        Totais totais = CalculoResumo.totais(exemplo(), BRL);

        assertThat(totais.entradas().texto()).isEqualTo("1000.00");
        assertThat(totais.saidas().texto()).isEqualTo("100.00");
        assertThat(totais.saldo().texto()).isEqualTo("900.00");
    }

    @Test
    void quebraPorCategoriaTemParticipacaoEVaiDoMaiorParaOMenor() {
        List<LinhaQuebra> linhas = CalculoResumo.quebrar(exemplo(), Quebra.CATEGORIA, f -> new Grupo(f.conta().toString(), null, f.descricao()), BRL);

        assertThat(linhas).extracting(LinhaQuebra::nome).containsExactly("Salário", "Mercado", "Farmácia");
        assertThat(linhas.get(1).participacaoSaidas()).isEqualByComparingTo("75");
        assertThat(linhas.get(2).participacaoSaidas()).isEqualByComparingTo("25");
        assertThat(linhas.get(1).participacaoEntradas()).isNull();
    }

    @Test
    void quebraPorContaOlhaOLadoDoDinheiro() {
        List<LinhaQuebra> linhas = CalculoResumo.quebrar(exemplo(), Quebra.CONTA, f -> new Grupo("itau", null, "Itaú"), BRL);

        assertThat(linhas).hasSize(1);
        assertThat(linhas.get(0).entradas().texto()).isEqualTo("1000.00");
        assertThat(linhas.get(0).saidas().texto()).isEqualTo("100.00");
    }

    @Test
    void maioresSomamOsMovimentosDoLancamento() {
        UUID nota = UUID.randomUUID();
        List<FatoPeriodo> fatos = List.of(fato(nota, TipoLancamento.GASTO, "Nota", false, "60.00"),
                fato(nota, TipoLancamento.GASTO, "Nota", false, "50.00"), fato(nota, TipoLancamento.GASTO, "Nota", true, "-110.00"),
                fato(UUID.randomUUID(), TipoLancamento.GASTO, "Café", false, "90.00"));

        assertThat(CalculoResumo.maiores(fatos, TipoLancamento.GASTO, 1, BRL)).singleElement()
                .satisfies(d -> assertThat(d.valor().texto()).isEqualTo("110.00"));
    }

    @Test
    void lancamentosDoRecorteSomamSoOLadoDaCategoriaEmOrdemDeData() {
        UUID nota = UUID.randomUUID();
        UUID salario = UUID.randomUUID();
        List<FatoPeriodo> fatos = List.of(fato(nota, TipoLancamento.GASTO, "Nota", false, "60.00"),
                fato(nota, TipoLancamento.GASTO, "Nota", false, "50.00"), fato(nota, TipoLancamento.GASTO, "Nota", true, "-110.00"),
                new FatoPeriodo(salario, "t-AAAAAA", TipoLancamento.GANHO, LocalDate.parse("2026-09-01"), "Salário", "Empresa", null,
                        UUID.randomUUID(), false, null, brl("-3000.00")));

        List<CalculoResumo.LancamentoDoPeriodo> lancamentos = CalculoResumo.lancamentos(fatos, BRL);

        assertThat(lancamentos).extracting(CalculoResumo.LancamentoDoPeriodo::descricao).containsExactly("Salário", "Nota");
        assertThat(lancamentos).extracting(l -> l.valor().texto()).containsExactly("3000.00", "110.00");
        assertThat(lancamentos.get(0).contraparte()).isEqualTo("Empresa");
    }

    @Test
    void variacaoSemBaseNaoTemPercentual() {
        assertThat(CalculoResumo.variacao(brl("150.00"), brl("100.00")).percentual()).isEqualByComparingTo("50");
        assertThat(CalculoResumo.variacao(brl("50.00"), brl("-100.00")).percentual()).isEqualByComparingTo("150");
        assertThat(CalculoResumo.variacao(brl("150.00"), brl("0.00")).percentual()).isNull();
    }

    @Test
    void periodosLidos() {
        assertThat(Periodo.ler("2026-02", "periodo")).isEqualTo(periodo("2026-02-01", "2026-02-28"));
        assertThat(Periodo.ler("2026", "periodo")).isEqualTo(periodo("2026-01-01", "2026-12-31"));
        assertThat(Periodo.ler("2026-W38", "periodo")).isEqualTo(periodo("2026-09-14", "2026-09-20"));
        assertThat(Periodo.ler("2026-W01", "periodo")).isEqualTo(periodo("2025-12-29", "2026-01-04"));
        assertThat(Periodo.ler("2026-W53", "periodo")).isEqualTo(periodo("2026-12-28", "2027-01-03"));
        for (String invalido : new String[] {"2026-13", "2025-W53", "2026-W00", "setembro"}) {
            assertThat(catchThrowableOfType(ErroTipado.class, () -> Periodo.ler(invalido, "periodo")).codigo())
                    .as(invalido).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        }
    }

    @Test
    void periodoAnteriorEquivalente() {
        assertThat(Periodo.ler("2026-03", "p").anterior()).isEqualTo(periodo("2026-02-01", "2026-02-28"));
        assertThat(Periodo.ler("2026-01", "p").anterior()).isEqualTo(periodo("2025-12-01", "2025-12-31"));
        assertThat(Periodo.ler("2026", "p").anterior()).isEqualTo(periodo("2025-01-01", "2025-12-31"));
        assertThat(Periodo.ler("2026-W38", "p").anterior()).isEqualTo(periodo("2026-09-07", "2026-09-13"));
        assertThat(periodo("2026-07-01", "2026-09-30").anterior()).isEqualTo(periodo("2026-04-01", "2026-06-30"));
        assertThat(periodo("2026-09-10", "2026-09-19").anterior()).isEqualTo(periodo("2026-08-31", "2026-09-09"));
    }

    private static Periodo periodo(String de, String ate) {
        return new Periodo(LocalDate.parse(de), LocalDate.parse(ate));
    }

    private static List<FatoPeriodo> exemplo() {
        UUID mercado = UUID.randomUUID();
        UUID farmacia = UUID.randomUUID();
        UUID salario = UUID.randomUUID();
        return List.of(fato(mercado, TipoLancamento.GASTO, "Mercado", false, "75.00"), fato(mercado, TipoLancamento.GASTO, "Mercado", true, "-75.00"),
                fato(farmacia, TipoLancamento.GASTO, "Farmácia", false, "25.00"), fato(farmacia, TipoLancamento.GASTO, "Farmácia", true, "-25.00"),
                fato(salario, TipoLancamento.GANHO, "Salário", false, "-1000.00"), fato(salario, TipoLancamento.GANHO, "Salário", true, "1000.00"));
    }

    private static FatoPeriodo fato(UUID lancamento, TipoLancamento tipo, String descricao, boolean comSaldo, String valor) {
        return new FatoPeriodo(lancamento, "t-" + lancamento, tipo, DIA, descricao, null, null, lancamento, comSaldo, null, brl(valor));
    }

    private static Dinheiro brl(String valor) {
        return Dinheiro.ler(valor, BRL, "valor");
    }
}
