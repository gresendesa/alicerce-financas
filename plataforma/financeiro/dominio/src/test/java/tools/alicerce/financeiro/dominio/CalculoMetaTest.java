package tools.alicerce.financeiro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Base;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Coluna;
import tools.alicerce.financeiro.dominio.CalculoProjecao.DataMeta;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Fluxo;
import tools.alicerce.financeiro.dominio.CalculoProjecao.MetaBase;
import tools.alicerce.financeiro.dominio.CalculoProjecao.Resultado;

class CalculoMetaTest {

    private static final LocalDate HOJE = LocalDate.parse("2026-11-07");
    private static final UUID VIAGEM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static Meta meta(long alvo, LocalDate data) {
        return new Meta(VIAGEM, "Viagem", List.of(), alvo, "BRL", data, 3, null, SituacaoMeta.ATIVA, null, false, 1);
    }

    @Test
    void oAporteNecessarioDivideAFaltaPelosMesesContandoOAtualEArredondaParaCima() {
        CalculoMeta.Situacao s = CalculoMeta.calcular(meta(600000, LocalDate.parse("2027-10-31")), 30000, HOJE);
        assertThat(s.falta()).isEqualTo(570000);
        assertThat(s.mesesRestantes()).isEqualTo(12);
        assertThat(s.aporteMensal()).isEqualTo(47500);
        assertThat(s.percentual()).isEqualByComparingTo("5.00");

        // 1.000,00 em 3 meses: 333,34, para a soma nunca ficar abaixo da falta.
        assertThat(CalculoMeta.calcular(meta(100000, LocalDate.parse("2027-01-01")), 0, HOJE).aporteMensal()).isEqualTo(33334);
    }

    @Test
    void semDataNaoHaAporteEAlcancadaOuVencidaSaoDitas() {
        CalculoMeta.Situacao sem = CalculoMeta.calcular(meta(100000, null), 20000, HOJE);
        assertThat(sem.aporteMensal()).isNull();
        assertThat(sem.mesesRestantes()).isNull();

        CalculoMeta.Situacao alcancada = CalculoMeta.calcular(meta(100000, LocalDate.parse("2027-01-01")), 120000, HOJE);
        assertThat(alcancada.alcancada()).isTrue();
        assertThat(alcancada.falta()).isZero();
        assertThat(alcancada.aporteMensal()).isZero();
        assertThat(alcancada.percentual()).isEqualByComparingTo("100.00");

        CalculoMeta.Situacao vencida = CalculoMeta.calcular(meta(100000, LocalDate.parse("2026-09-30")), 40000, HOJE);
        assertThat(vencida.prazoVencido()).isTrue();
        assertThat(vencida.mesesRestantes()).isEqualTo(1);
        assertThat(vencida.aporteMensal()).isEqualTo(60000);
    }

    @Test
    void oPlanoDeAportesParaQuandoAMetaChegaNoAlvo() {
        assertThat(CalculoMeta.planoDeAportes(30000, 60000, 10000, 5)).containsExactly(10000, 10000, 10000, 0, 0);
        assertThat(CalculoMeta.planoDeAportes(30000, 55000, 10000, 4)).containsExactly(10000, 10000, 5000, 0);
        assertThat(CalculoMeta.planoDeAportes(70000, 60000, 10000, 2)).containsExactly(0, 0);
        assertThatThrownBy(() -> CalculoMeta.planoDeAportes(0, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void naProjecaoOsAportesSaemDoSaldoEDaoAData() {
        Base b = new Base(HOJE, 4, 100000, List.of(), List.of(), 0, null, (c, d) -> d, List.of(new MetaBase(VIAGEM, 10000, 40000,
                LocalDate.parse("2027-01-31"))));
        Resultado base = CalculoProjecao.projetar(b, List.of());
        assertThat(base.metas()).containsExactly(new DataMeta(VIAGEM, 0, null, null));

        Resultado r = CalculoProjecao.projetar(b, List.of(new CalculoProjecao.Fluxos(List.of(
                new Fluxo(Coluna.APORTE, HOJE, 10000, VIAGEM), new Fluxo(Coluna.APORTE, LocalDate.parse("2026-12-01"), 10000, VIAGEM),
                new Fluxo(Coluna.APORTE, LocalDate.parse("2027-01-01"), 10000, VIAGEM)))));
        assertThat(r.meses()).extracting(CalculoProjecao.Mes::aportes).containsExactly(10000L, 10000L, 10000L, 0L);
        assertThat(r.meses()).extracting(CalculoProjecao.Mes::finalP50).containsExactly(90000L, 80000L, 70000L, 70000L);
        assertThat(r.metas()).containsExactly(new DataMeta(VIAGEM, 30000, YearMonth.parse("2027-01"), true));

        Resultado tarde = CalculoProjecao.projetar(b, List.of(new CalculoProjecao.Fluxos(List.of(
                new Fluxo(Coluna.APORTE, LocalDate.parse("2027-02-01"), 30000, VIAGEM)))));
        assertThat(tarde.metas()).containsExactly(new DataMeta(VIAGEM, 30000, YearMonth.parse("2027-02"), false));
        assertThatThrownBy(() -> new Fluxo(Coluna.APORTE, HOJE, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
