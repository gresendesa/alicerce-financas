package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.ErroTipado;

class CartaoCreditoTest {

    private static CartaoCredito cartao(int fechamento, int vencimento) {
        return new CartaoCredito(UUID.randomUUID(), fechamento, vencimento, null, null, 1);
    }

    @Test
    void compraNoDiaDoFechamentoEntraNaFaturaQueFechaAli() {
        CicloFatura ciclo = cartao(10, 17).cicloDaCompra(LocalDate.parse("2026-09-10"));

        assertThat(ciclo.fechaEm()).isEqualTo("2026-09-10");
        assertThat(ciclo.venceEm()).isEqualTo("2026-09-17");
        assertThat(ciclo.periodo()).isEqualTo("2026-09");
    }

    @Test
    void compraNoDiaSeguinteVaiParaAProxima() {
        CicloFatura ciclo = cartao(10, 17).cicloDaCompra(LocalDate.parse("2026-09-11"));

        assertThat(ciclo.fechaEm()).isEqualTo("2026-10-10");
        assertThat(ciclo.periodo()).isEqualTo("2026-10");
    }

    @Test
    void vencimentoAntesDoFechamentoCaiNoMesSeguinteEViraOAno() {
        CicloFatura ciclo = cartao(25, 5).cicloDaCompra(LocalDate.parse("2026-12-26"));

        assertThat(ciclo.fechaEm()).isEqualTo("2027-01-25");
        assertThat(ciclo.venceEm()).isEqualTo("2027-02-05");
        assertThat(ciclo.periodo()).isEqualTo("2027-02");
    }

    @Test
    void diaMaiorQueOMesCaiNoUltimoDia() {
        CartaoCredito c = cartao(31, 30);

        CicloFatura fevereiro = c.cicloDaCompra(LocalDate.parse("2027-02-28"));
        assertThat(fevereiro.fechaEm()).isEqualTo("2027-02-28");
        assertThat(fevereiro.venceEm()).isEqualTo("2027-03-30");

        CicloFatura bissexto = c.cicloDaCompra(LocalDate.parse("2028-02-29"));
        assertThat(bissexto.fechaEm()).isEqualTo("2028-02-29");

        // Vence em fevereiro: o dia 30 cai no último dia.
        assertThat(c.cicloDaCompra(LocalDate.parse("2027-01-31")).venceEm()).isEqualTo("2027-02-28");
        // Abril não tem 31: compra no dia 30 fecha ali, e a do dia seguinte já é maio.
        assertThat(c.cicloDaCompra(LocalDate.parse("2027-04-30")).fechaEm()).isEqualTo("2027-04-30");
        assertThat(c.cicloDaCompra(LocalDate.parse("2027-05-01")).fechaEm()).isEqualTo("2027-05-31");
    }

    @Test
    void cadaCicloTemSeuProprioMesDeVencimentoEmQualquerConfiguracao() {
        for (int fechamento = 1; fechamento <= 31; fechamento++) {
            for (int vencimento = 1; vencimento <= 31; vencimento++) {
                CartaoCredito c = cartao(fechamento, vencimento);
                CicloFatura ciclo = c.cicloDaCompra(LocalDate.parse("2027-01-01"));
                Set<String> periodos = new HashSet<>();
                for (int i = 0; i < 60; i++) {
                    assertThat(periodos.add(ciclo.periodo())).as("%d/%d em %s", fechamento, vencimento, ciclo).isTrue();
                    assertThat(ciclo.venceEm()).isAfterOrEqualTo(ciclo.fechaEm());
                    ciclo = c.seguinte(ciclo);
                }
            }
        }
    }

    @Test
    void compraDeQualquerDiaEntraEmCicloAindaAberto() {
        CartaoCredito c = cartao(31, 10);
        for (LocalDate d = LocalDate.parse("2027-01-01"); d.getYear() < 2029; d = d.plusDays(1)) {
            assertThat(c.cicloDaCompra(d).fechaEm()).isAfterOrEqualTo(d);
        }
    }

    @Test
    void proximoAVencerPodeJaTerFechado() {
        CartaoCredito c = cartao(10, 17);

        assertThat(c.proximoAVencer(LocalDate.parse("2026-09-12")).periodo()).isEqualTo("2026-09");
        assertThat(c.proximoAVencer(LocalDate.parse("2026-09-17")).periodo()).isEqualTo("2026-09");
        assertThat(c.proximoAVencer(LocalDate.parse("2026-09-18")).periodo()).isEqualTo("2026-10");
        assertThat(c.proximoAVencer(LocalDate.parse("2026-09-05")).periodo()).isEqualTo("2026-09");
    }

    @Test
    void diaForaDoMesELimiteNegativoSaoRecusados() {
        assertThatThrownBy(() -> cartao(0, 10)).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> cartao(10, 32)).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> new CartaoCredito(UUID.randomUUID(), 1, 2, -1L, null, 1)).isInstanceOf(ErroTipado.class);
    }

    @Test
    void ciclosDaCompraParceladaPulamFaturaGravadaQueJaFechouEUsamOVencimentoDela() {
        CartaoCredito cartao = cartao(10, 17);
        // Os dias mudaram depois: a fatura de 2026-09 gravada fechou no dia 5 e vence no dia 12.
        Fatura gravada = new Fatura(UUID.randomUUID(), cartao.conta(), "2026-09", LocalDate.parse("2026-09-05"), LocalDate.parse("2026-09-12"));
        java.util.function.Function<String, java.util.Optional<Fatura>> existente = p -> java.util.Optional.ofNullable(
                p.equals("2026-09") ? gravada : null);

        java.util.List<CicloFatura> ciclos = cartao.ciclosDaCompra(LocalDate.parse("2026-09-08"), 3, existente);
        assertThat(ciclos).extracting(CicloFatura::periodo).containsExactly("2026-10", "2026-11", "2026-12");

        java.util.List<CicloFatura> antes = cartao.ciclosDaCompra(LocalDate.parse("2026-09-03"), 2, existente);
        assertThat(antes).extracting(CicloFatura::periodo).containsExactly("2026-09", "2026-10");
        assertThat(CartaoCredito.vencimento(antes.get(0), existente)).isEqualTo("2026-09-12");
        assertThat(CartaoCredito.vencimento(antes.get(1), existente)).isEqualTo("2026-10-17");
    }
}
