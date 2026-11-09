package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Base;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Coluna;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Fluxo;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Mes;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Regra;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Resultado;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Violacao;

/** As contas das regras do perfil. */
class CalculoPerfilTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-11-10");
    private static final UUID VIAGEM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void gastoTipicoEReservaEmMeses() {
        assertThat(CalculoPerfil.gastoMensalTipico(List.of(20000L, 70000L))).isNull();
        assertThat(CalculoPerfil.gastoMensalTipico(List.of(20000L, 30000L, 40000L, 50000L, 60000L, 70000L))).isEqualTo(45000L);
        ValorDeclaracao.ReservaMinima meses = new ValorDeclaracao.ReservaMinima(null, 3);
        assertThat(CalculoPerfil.reserva(meses, 45000L)).isEqualTo(135000L);
        assertThat(CalculoPerfil.reserva(meses, null)).isNull();
        assertThat(CalculoPerfil.reserva(new ValorDeclaracao.ReservaMinima(500000L, null), null)).isEqualTo(500000L);
        assertThatThrownBy(() -> new ValorDeclaracao.ReservaMinima(100L, 2)).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> new ValorDeclaracao.ReservaMinima(null, 0)).isInstanceOf(ErroTipado.class);
    }

    @Test
    void percentuaisDeclaradosTemLimitesEDuasCasas() {
        assertThat(new ValorDeclaracao.IntencaoPoupanca(new BigDecimal("12.5")).percentual()).isEqualByComparingTo("12.50");
        assertThat(new ValorDeclaracao.IntencaoPoupanca(BigDecimal.ZERO).percentual().toPlainString()).isEqualTo("0.00");
        assertThatThrownBy(() -> new ValorDeclaracao.TetoParcelas(BigDecimal.ZERO)).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> new ValorDeclaracao.TetoParcelas(new BigDecimal("100.01"))).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> new ValorDeclaracao.IntencaoPoupanca(new BigDecimal("1.234"))).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> new ValorDeclaracao.Nota("x".repeat(1001))).isInstanceOf(ErroTipado.class);
        assertThat(new ValorDeclaracao.Nota("  oi ").texto()).isEqualTo("oi");
    }

    @Test
    void comprometimentoEPoupancaSemRendaNaoSaoAvaliados() {
        assertThat(CalculoPerfil.comprometimento(50000, 100000)).isEqualByComparingTo("50.00");
        assertThat(CalculoPerfil.comprometimento(1, 3)).isEqualByComparingTo("33.33");
        assertThat(CalculoPerfil.comprometimento(50000, 0)).isNull();
        assertThat(CalculoPerfil.acimaDoTeto(null, BigDecimal.ONE)).isFalse();
        assertThat(CalculoPerfil.acimaDoTeto(new BigDecimal("25.00"), new BigDecimal("25"))).isFalse();

        CalculoPerfil.Poupanca p = CalculoPerfil.poupanca(300000, 180000);
        assertThat(p.percentual()).isEqualByComparingTo("40.00");
        assertThat(CalculoPerfil.poupancaAbaixo(p, new BigDecimal("50"))).isTrue();
        assertThat(CalculoPerfil.poupancaAbaixo(p, new BigDecimal("40"))).isFalse();
        assertThat(CalculoPerfil.poupancaAbaixo(CalculoPerfil.poupanca(100, 500), BigDecimal.ZERO)).as("não poupar nunca dispara").isFalse();
        assertThat(CalculoPerfil.poupanca(100, 500).percentual()).isEqualByComparingTo("-400.00");
        assertThat(CalculoPerfil.poupancaAbaixo(CalculoPerfil.poupanca(0, 500), BigDecimal.TEN)).isFalse();
    }

    @Test
    void parcelasSubindoExigemTresAltasSeguidas() {
        assertThat(CalculoPerfil.parcelasSubindo(List.of(0L, 100L, 200L, 300L))).isTrue();
        assertThat(CalculoPerfil.parcelasSubindo(List.of(100L, 200L, 200L, 300L))).isFalse();
        assertThat(CalculoPerfil.parcelasSubindo(List.of(0L, 0L, 0L, 0L))).isFalse();
        assertThatThrownBy(() -> CalculoPerfil.parcelasSubindo(List.of(1L, 2L, 3L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CalculoPerfil.severidadeReserva(0)).isEqualTo(CalculoSituacao.Severidade.CRITICO);
        assertThat(CalculoPerfil.severidadeReserva(1)).isEqualTo(CalculoSituacao.Severidade.CRITICO);
        assertThat(CalculoPerfil.severidadeReserva(2)).isEqualTo(CalculoSituacao.Severidade.AVISO);
    }

    @Test
    void metaAtrasadaPeloRitmoDosAportes() {
        // Falta 6.000,00 em 6 meses (nov a abr): 1.000,00 por mês.
        Meta viagem = meta(600000, LocalDate.parse("2027-04-30"));
        assertThat(CalculoPerfil.metaAtrasada(viagem, 0, 300000, HOJE)).as("1.000,00 por mês chega").isEmpty();
        CalculoPerfil.RitmoMeta atrasada = CalculoPerfil.metaAtrasada(viagem, 0, 150000, HOJE).orElseThrow();
        assertThat(atrasada.ritmoMensal()).isEqualTo(50000);
        assertThat(atrasada.aporteNecessario()).isEqualTo(100000);
        assertThat(atrasada.mesProjetado()).isEqualTo(YearMonth.parse("2027-10"));
        assertThat(CalculoPerfil.metaAtrasada(viagem, 0, -30000, HOJE).orElseThrow().mesProjetado()).isNull();
        assertThat(CalculoPerfil.metaAtrasada(viagem, 600000, 0, HOJE)).as("já alcançou").isEmpty();
        assertThat(CalculoPerfil.metaAtrasada(meta(600000, null), 0, 0, HOJE)).as("sem data").isEmpty();
        assertThat(CalculoPerfil.metaAtrasada(meta(600000, LocalDate.parse("2026-10-31")), 0, 900000, HOJE).orElseThrow().prazoVencido()).isTrue();
        assertThat(CalculoPerfil.severidadeMeta(true)).isEqualTo(CalculoSituacao.Severidade.CRITICO);
    }

    @Test
    void variabilidadePeloPostoMaisProximo() {
        CalculoPerfil.Variabilidade v = CalculoPerfil.variabilidade(List.of(60000L, 10000L, 30000L, 20000L, 50000L, 40000L));
        assertThat(v.menor()).isEqualTo(10000);
        assertThat(v.mediana()).isEqualTo(35000);
        assertThat(v.maior()).isEqualTo(60000);
        assertThat(v.p25()).isEqualTo(20000);
        assertThat(v.p75()).isEqualTo(50000);
        assertThat(v.dispersao()).isEqualByComparingTo("85.71");
        assertThat(CalculoPerfil.variabilidade(List.of(0L, 0L, 100L)).dispersao()).isNull();
    }

    @Test
    void aProjecaoSeparaAsParcelasEApontaAsViolacoes() {
        LocalDate dezembro = LocalDate.parse("2026-12-10");
        List<Fluxo> fluxos = List.of(new Fluxo(Coluna.ENTRADA, LocalDate.parse("2026-12-05"), 100000),
                new Fluxo(Coluna.ENTRADA, LocalDate.parse("2027-01-05"), 100000));
        CalculoProjecao.Perfil perfil = new CalculoProjecao.Perfil(80000L, new BigDecimal("25"), Map.of(YearMonth.parse("2026-12"), 10000L), 50000);
        Base b = new Base(HOJE, 3, 100000, fluxos, List.of(), 0, 50000L, (c, d) -> d,
                List.of(new CalculoProjecao.MetaBase(VIAGEM, 0, 60000, LocalDate.parse("2027-01-31"), true)), perfil);
        List<CalculoProjecao.Hipotese> compra = List.of(new CalculoProjecao.Fluxos(List.of(Fluxo.parcela(Coluna.FATURA, dezembro, 30000),
                Fluxo.parcela(Coluna.FATURA, LocalDate.parse("2027-01-10"), 30000))));

        Resultado base = CalculoProjecao.projetar(b, List.of());
        assertThat(base.meses()).extracting(Mes::renda).containsExactly(50000L, 100000L, 100000L);
        assertThat(base.meses()).extracting(Mes::parcelas).containsExactly(0L, 10000L, 0L);
        assertThat(base.meses().get(1).comprometimento()).isEqualByComparingTo("10.00");
        assertThat(base.violacoes()).isEmpty();
        assertThat(base.mesesSemRenda()).isEmpty();

        Resultado r = CalculoProjecao.projetar(b, compra);
        assertThat(r.meses()).extracting(Mes::parcelas).containsExactly(0L, 40000L, 30000L);
        assertThat(r.meses()).extracting(Mes::faturas).containsExactly(0L, 30000L, 30000L);
        assertThat(r.meses()).extracting(Mes::finalP80).containsExactly(100000L, 170000L, 240000L);
        assertThat(r.picoParcelas().percentual()).isEqualByComparingTo("40.00");
        assertThat(r.violacoes()).extracting(Violacao::regra, Violacao::mes).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Regra.TETO_PARCELAS, YearMonth.parse("2026-12")),
                org.assertj.core.groups.Tuple.tuple(Regra.TETO_PARCELAS, YearMonth.parse("2027-01")));

        // Meta protegida: o piso de 500,00 mais 200,00 por mês até janeiro não cabe num saldo de 600,00.
        Base apertada = new Base(HOJE, 2, 60000, List.of(), List.of(), 0, 50000L, (c, d) -> d,
                List.of(new CalculoProjecao.MetaBase(VIAGEM, 0, 60000, LocalDate.parse("2027-01-31"), true)), perfil);
        Resultado m = CalculoProjecao.projetar(apertada, List.of());
        assertThat(m.abaixoReservaP80()).containsExactly(YearMonth.parse("2026-11"), YearMonth.parse("2026-12"));
        assertThat(m.violacoes()).extracting(Violacao::regra).containsExactly(Regra.RESERVA_MINIMA, Regra.RESERVA_MINIMA, Regra.META_PROTEGIDA,
                Regra.META_PROTEGIDA);
        Violacao meta = m.violacoes().get(2);
        assertThat(meta.meta()).isEqualTo(VIAGEM);
        assertThat(meta.aporteNecessario()).isEqualTo(20000);
        assertThat(meta.limite()).isEqualTo(70000);
        assertThat(m.mesesSemRenda()).containsExactly(YearMonth.parse("2026-12"));
    }

    private static Meta meta(long alvo, LocalDate data) {
        return new Meta(VIAGEM, "Viagem", List.of(), alvo, "BRL", data, 3, null, SituacaoMeta.ATIVA, null, false, 1);
    }
}
