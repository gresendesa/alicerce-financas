package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;

class RepartirTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @Test
    void sobraVaiParaAPrimeiraParcela() {
        assertThat(Repartir.parcelas(10000, 3)).containsExactly(3334, 3333, 3333);
        assertThat(Repartir.parcelas(10000, 7)).containsExactly(1432, 1428, 1428, 1428, 1428, 1428, 1428);
        assertThat(Repartir.parcelas(9000, 3)).containsExactly(3000, 3000, 3000);
        assertThat(Repartir.parcelas(4999, 1)).containsExactly(4999);
    }

    @Test
    void parcelasSomamExatamenteOTotalDeUmA48() {
        Random sorteio = new Random(42);
        for (int n = 1; n <= 48; n++) {
            for (int i = 0; i < 200; i++) {
                long total = n + sorteio.nextLong(100_000_000L);
                assertThat(Arrays.stream(Repartir.parcelas(total, n)).sum()).isEqualTo(total);
            }
        }
    }

    @Test
    void foraDosLimitesERecusado() {
        assertThatThrownBy(() -> Repartir.parcelas(10000, 0)).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> Repartir.parcelas(10000, 49)).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> Repartir.parcelas(2, 3)).isInstanceOf(ErroTipado.class);
    }

    @Test
    void matrizFechaLinhasEColunasSemNegativos() {
        Random sorteio = new Random(7);
        for (int rodada = 0; rodada < 5000; rodada++) {
            int quantidade = 1 + sorteio.nextInt(48);
            int partes = 1 + sorteio.nextInt(6);
            long[] colunas = new long[partes];
            long total = 0;
            for (int j = 0; j < partes; j++) {
                colunas[j] = 1 + sorteio.nextInt(rodada % 2 == 0 ? 5 : 1_000_000);
                total += colunas[j];
            }
            if (total < quantidade) {
                continue;
            }
            long[] linhas = Repartir.parcelas(total, quantidade);
            long[][] m = Repartir.matriz(linhas, colunas);
            long[] somaColunas = new long[partes];
            for (int i = 0; i < quantidade; i++) {
                assertThat(Arrays.stream(m[i]).sum()).isEqualTo(linhas[i]);
                for (int j = 0; j < partes; j++) {
                    assertThat(m[i][j]).isNotNegative();
                    somaColunas[j] += m[i][j];
                }
            }
            assertThat(somaColunas).containsExactly(colunas);
        }
    }

    @Test
    void compraParceladaSomaZeroEFicaUmaParcelaPorFatura() {
        UUID cartao = UUID.randomUUID();
        UUID mercado = UUID.randomUUID();
        UUID casa = UUID.randomUUID();
        UUID eu = UUID.randomUUID();
        UUID mae = UUID.randomUUID();
        List<UUID> faturas = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Dinheiro total = Dinheiro.deCentavos(10000, BRL);
        List<Partida> partidas = List.of(new Partida(mercado, eu, Dinheiro.deCentavos(5001, BRL)),
                new Partida(casa, mae, Dinheiro.deCentavos(4999, BRL)));

        List<Movimento> movimentos = Partidas.gastoNoCredito(total, cartao, partidas, faturas);

        assertThat(movimentos.stream().mapToLong(m -> m.valor().centavos()).sum()).isZero();
        List<Long> porFatura = new ArrayList<>();
        for (UUID fatura : faturas) {
            porFatura.add(-movimentos.stream().filter(m -> fatura.equals(m.fatura())).mapToLong(m -> m.valor().centavos()).sum());
        }
        assertThat(porFatura).containsExactly(3334L, 3333L, 3333L);
        assertThat(movimentos.stream().filter(m -> m.conta().equals(cartao))).allMatch(m -> m.totalParcelas() == 3);
        assertThat(movimentos.stream().filter(m -> !m.conta().equals(cartao))).allMatch(m -> m.fatura() == null);
    }

    @Test
    void compraAVistaNoCartaoNaoTemParcela() {
        UUID cartao = UUID.randomUUID();
        UUID fatura = UUID.randomUUID();
        List<Movimento> movimentos = Partidas.gastoNoCredito(Dinheiro.deCentavos(4590, BRL), cartao,
                List.of(new Partida(UUID.randomUUID(), UUID.randomUUID(), Dinheiro.deCentavos(4590, BRL))), List.of(fatura));

        Movimento noCartao = movimentos.stream().filter(m -> m.conta().equals(cartao)).findFirst().orElseThrow();
        assertThat(noCartao.fatura()).isEqualTo(fatura);
        assertThat(noCartao.parcela()).isNull();
        assertThat(noCartao.parcelaTexto()).isNull();
    }
}
