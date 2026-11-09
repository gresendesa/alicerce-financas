package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.financeiro.dominio.CompraNoCredito.Lado;

class HistoricoTest {

    private static final java.util.Currency BRL = java.util.Currency.getInstance("BRL");
    private final UUID categoria = UUID.randomUUID();
    private final UUID ambito = UUID.randomUUID();
    private final UUID outroAmbito = UUID.randomUUID();
    private final UUID cartao = UUID.randomUUID();
    private final UUID tecnica = UUID.randomUUID();

    @Test
    void oMarcoJaECobertoSoOQueVemAntesEExogeno() {
        LocalDate marco = LocalDate.parse("2026-11-01");

        assertThat(Procedencia.de(marco.minusDays(1), marco)).isEqualTo(Procedencia.EXOGENO);
        assertThat(Procedencia.de(marco, marco)).isEqualTo(Procedencia.ENDOGENO);
        assertThat(Procedencia.de(marco, null)).isEqualTo(Procedencia.ENDOGENO);
    }

    @Test
    void linhasAntigasSoTemAberturaExogena() {
        assertThat(Procedencia.legado(TipoLancamento.SALDO_ABERTURA)).isEqualTo(Procedencia.EXOGENO);
        assertThat(Procedencia.legado(TipoLancamento.GASTO)).isEqualTo(Procedencia.ENDOGENO);
    }

    @Test
    void aParcelaAtualSaiDaDataEDosDiasDoCartao() {
        CartaoCredito nubank = new CartaoCredito(cartao, 18, 26, null, null, 1);
        LocalDate hoje = LocalDate.parse("2026-11-01");

        assertThat(nubank.parcelaEm(LocalDate.parse("2026-04-10"), hoje)).isEqualTo(8);
        assertThat(nubank.parcelaEm(LocalDate.parse("2026-03-19"), hoje)).isEqualTo(8);
        assertThat(nubank.parcelaEm(LocalDate.parse("2026-04-19"), hoje)).isEqualTo(7);
        assertThat(nubank.parcelaEm(LocalDate.parse("2026-10-01"), hoje)).isEqualTo(2);
    }

    @Test
    void compraEmAndamentoSomaZeroComANumeracaoReal() {
        UUID abertura = UUID.randomUUID();
        UUID fatura = UUID.randomUUID();
        List<DestinoParcela> destinos = List.of(DestinoParcela.anterior(tecnica), DestinoParcela.naAbertura(tecnica, abertura),
                DestinoParcela.noCartao(cartao, fatura));

        List<Movimento> movimentos = Partidas.gastoEmParcelas(reais(10000), cartao, List.of(new Partida(categoria, ambito, reais(10000))), destinos);

        assertThat(movimentos.stream().mapToLong(m -> m.valor().centavos()).sum()).isZero();
        assertThat(movimentos).filteredOn(m -> m.conta().equals(categoria)).singleElement()
                .satisfies(m -> assertThat(m.valor().centavos()).isEqualTo(10000));
        Movimento primeira = parcela(movimentos, 1);
        Movimento segunda = parcela(movimentos, 2);
        Movimento terceira = parcela(movimentos, 3);
        assertThat(primeira.conta()).isEqualTo(tecnica);
        assertThat(primeira.fatura()).isNull();
        assertThat(primeira.valor().centavos()).isEqualTo(-3334);
        assertThat(segunda.faturaAbertura()).isEqualTo(abertura);
        assertThat(segunda.conta()).isEqualTo(tecnica);
        assertThat(terceira.conta()).isEqualTo(cartao);
        assertThat(terceira.fatura()).isEqualTo(fatura);
        assertThat(terceira.totalParcelas()).isEqualTo(3);
    }

    @Test
    void compraEmAndamentoComDoisAmbitosFechaAoCentavo() {
        List<DestinoParcela> destinos = List.of(DestinoParcela.anterior(tecnica), DestinoParcela.noCartao(cartao, UUID.randomUUID()));

        List<Movimento> movimentos = Partidas.gastoEmParcelas(reais(10001), cartao,
                List.of(new Partida(categoria, ambito, reais(3333)), new Partida(categoria, outroAmbito, reais(6668))), destinos);

        assertThat(movimentos.stream().mapToLong(m -> m.valor().centavos()).sum()).isZero();
        assertThat(movimentos).filteredOn(m -> m.parcela() != null).allMatch(m -> m.valor().ehNegativo());
    }

    @Test
    void oEstornoLevaALigacaoComAAbertura() {
        UUID abertura = UUID.randomUUID();
        Movimento movimento = new Movimento(tecnica, reais(-100), ambito, null, 8, 12, abertura);

        assertThat(movimento.invertido().faturaAbertura()).isEqualTo(abertura);
        assertThat(movimento.invertido().parcelaTexto()).isEqualTo("8/12");
    }

    @Test
    void naCaixaParcelaSemVencimentoEntraNaDivisaoENuncaConta() {
        LocalDate vencimento = LocalDate.parse("2026-12-26");
        List<Lado> parcelas = new ArrayList<>(List.of(new Lado(tecnica, ambito, reais(5000), 1, null), new Lado(tecnica, ambito, reais(5000), 2, null),
                new Lado(cartao, ambito, reais(5000), 3, vencimento)));
        CompraNoCredito compra = new CompraNoCredito(UUID.randomUUID(), "t-AAAAAA", "Relógio", null, null,
                List.of(new Lado(categoria, ambito, reais(15000), 0, null)), parcelas);

        List<FatoPeriodo> dezembro = CalculoCaixa.fatos(compra, new Periodo(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31")), null);
        List<FatoPeriodo> ano = CalculoCaixa.fatos(compra, new Periodo(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")), null);

        assertThat(dezembro).filteredOn(f -> !f.contaComSaldo()).singleElement()
                .satisfies(f -> assertThat(f.valor().centavos()).isEqualTo(5000));
        assertThat(ano.stream().filter(f -> !f.contaComSaldo()).mapToLong(f -> f.valor().centavos()).sum()).isEqualTo(5000);
        assertThat(ano).noneMatch(FatoPeriodo::exogeno);
    }

    @Test
    void oExogenoSaiAPartESemMudarOsTotais() {
        Dinheiro cem = reais(10000);
        FatoPeriodo dentro = new FatoPeriodo(UUID.randomUUID(), "t-A", TipoLancamento.GASTO, LocalDate.parse("2026-10-01"), "A", null, null, categoria, false, ambito, cem, false);
        FatoPeriodo fora = new FatoPeriodo(UUID.randomUUID(), "t-B", TipoLancamento.GASTO, LocalDate.parse("2026-10-01"), "B", null, null, categoria, false, ambito, cem, true);

        assertThat(CalculoResumo.totais(List.of(dentro, fora), BRL).saidas().centavos()).isEqualTo(20000);
        assertThat(CalculoResumo.exogenos(List.of(dentro, fora), BRL).saidas().centavos()).isEqualTo(10000);
    }

    private static Movimento parcela(List<Movimento> movimentos, int numero) {
        return movimentos.stream().filter(m -> Objects.equals(m.parcela(), numero)).findFirst().orElseThrow();
    }

    private static Dinheiro reais(long centavos) {
        return Dinheiro.deCentavos(centavos, BRL);
    }
}
