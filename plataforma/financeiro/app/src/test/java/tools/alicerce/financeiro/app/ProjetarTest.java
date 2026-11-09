package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.CortesVisto;
import tools.alicerce.financeiro.aplicacao.CreditoVisto;
import tools.alicerce.financeiro.aplicacao.Declarar;
import tools.alicerce.financeiro.aplicacao.DefinirCompromisso;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.OperacaoVista;
import tools.alicerce.financeiro.aplicacao.ProjecaoRegistrada;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista.CenarioVisto;
import tools.alicerce.financeiro.aplicacao.ProjecaoVista.MesVisto;
import tools.alicerce.financeiro.aplicacao.Projetar;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.RetratoVisto;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;

/**
 * Testes de aceite ({@code projetar}), nos casos de uso, contra o banco. As datas saem de hoje;
 * todo gasto do histórico cai no dia 1, para os números não dependerem do dia em que o teste roda.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresDeTeste.class)
class ProjetarTest {

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirCompromisso definirCompromisso;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired Declarar declarar;
    @Autowired Projetar projetar;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired Vistas vistas;
    @Autowired DSLContext banco;

    private UUID dono;
    private Chamador chamador;
    private LocalDate hoje;
    private YearMonth mes;

    @BeforeEach
    void dono() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:profile"), "gui");
        hoje = LocalDate.now(ZoneId.of(banco.fetchValue("select fuso from dono where id = ?", dono).toString()));
        mes = YearMonth.from(hoje);
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    /**
     * O mesmo de {@code CortesNecessariosTest}: Itaú com saldo de partida de 2.180,00; Mercado com mediana de 350,00 e
     * percentil 80 de 500,00, 120,00 já gastos neste mês; Lazer com 100,00 por mês. Um bônus de 300,00 no fim deste mês e
     * um aluguel de 1.000,00 todo dia 5, a partir do mês que vem.
     */
    private void cenario() {
        conta("corrente", "Itaú", "5000.00", mes.minusMonths(7).atDay(1).toString());
        conta("categoria", "Mercado", null, null);
        conta("categoria", "Lazer", null, null);
        conta("categoria", "Moradia", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        for (int i = 6; i >= 1; i--) {
            gasto((7 - i) + "00.00", mes.minusMonths(i).atDay(1), "Mercado", "Pix");
            gasto("100.00", mes.minusMonths(i).atDay(1), "Lazer", "Pix");
        }
        gasto("120.00", mes.atDay(1), "Mercado", "Pix");
        compromisso("saida", "recorrente", "Aluguel", "1000.00", mes.plusMonths(1).atDay(5));
        compromisso("entrada", "unico", "Bônus", "300.00", mes.atEndOfMonth());
    }

    @Test
    void aLinhaDeBaseSomaOsQuatroFluxosNasDuasEstimativas() {
        cenario();
        declarar("1000.00");
        ProjecaoRegistrada registrada = projetar.executar(chamador, new Projetar.Entrada(3, null));
        ProjecaoVista p = registrada.projecao();

        assertThat(registrada.retrato()).startsWith("s-");
        assertThat(p.hoje()).isEqualTo(hoje.toString());
        assertThat(p.ate()).isEqualTo(mes.plusMonths(2).atEndOfMonth().toString());
        assertThat(p.saldoPartida()).isEqualTo("2180.00");
        assertThat(p.estimativaVariavel()).isTrue();
        assertThat(p.mesesReferencia()).isEqualTo(6);
        assertThat(p.piso().saldoMinimo()).isEqualTo("1000.00");
        assertThat(p.cenarios()).isEmpty();

        CenarioVisto base = p.linhaBase();
        assertThat(base.diferencaLinhaBase()).isNull();
        assertThat(base.meses()).extracting(MesVisto::mes).containsExactly(mes.toString(), mes.plusMonths(1).toString(), mes.plusMonths(2).toString());
        assertThat(base.meses()).extracting(MesVisto::entradas).containsExactly("300.00", "0.00", "0.00");
        assertThat(base.meses()).extracting(MesVisto::saidasConhecidas).containsExactly("0.00", "1000.00", "1000.00");
        // Mercado: o restante do mês (350,00 − 120,00 e 500,00 − 120,00), depois o mês inteiro; Lazer: 100,00 em todos.
        assertThat(base.meses()).extracting(MesVisto::variavelP50).containsExactly("330.00", "450.00", "450.00");
        assertThat(base.meses()).extracting(MesVisto::variavelP80).containsExactly("480.00", "600.00", "600.00");
        assertThat(base.meses()).extracting(MesVisto::saldoFinalP50).containsExactly("2150.00", "700.00", "-750.00");
        assertThat(base.meses()).extracting(MesVisto::saldoFinalP80).containsExactly("2000.00", "400.00", "-1200.00");
        assertThat(base.meses().get(1).saldoInicialP80()).isEqualTo("2000.00");
        assertThat(base.indicadores().menorSaldoP80().saldo()).isEqualTo("-1200.00");
        assertThat(base.indicadores().menorSaldoP80().mes()).isEqualTo(mes.plusMonths(2).toString());
        assertThat(base.indicadores().mesesAbaixoPisoP50()).containsExactly(mes.plusMonths(1).toString(), mes.plusMonths(2).toString());
        assertThat(base.meses().get(1).abaixoPisoP80()).isTrue();
        assertThat(base.indicadores().picoFaturas()).isNull();
        assertThat(p.ressalvas()).extracting(CortesVisto.Ressalva::codigo).containsExactly("conferencia_vencida");
        assertThat(p.notas()).anyMatch(n -> n.contains("não é previsão"));
    }

    @Test
    void aCompraEm10xPoeCadaParcelaNaFaturaQueORegistrarGastoPoria() {
        cenario();
        cartao("Nubank", 10, 17);
        List<CreditoVisto.ParcelaVista> real = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(null, null, "3000.00", "TV", "Nubank",
                "conversa", hoje.toString(), "Lazer", null, null, null, null, null, null, null, true, null, 10, null)).valor().credito().faturas();

        ProjecaoVista p = projetar.executar(chamador, new Projetar.Entrada(12, List.of(
                new Projetar.Cenario("à vista no Pix", List.of(compra("3000.00", "Pix", null))),
                new Projetar.Cenario("10x no Nubank", List.of(compra("3000.00", "Nubank", 10)))))).projecao();

        CenarioVisto parcelado = p.cenarios().get(1);
        assertThat(parcelado.rotulo()).isEqualTo("10x no Nubank");
        ProjecaoVista.HipoteseVista tv = parcelado.hipoteses().get(0);
        assertThat(tv.tipo()).isEqualTo("compra");
        assertThat(tv.resumo()).contains("R$ 3.000,00", "Nubank", "10 parcelas");
        assertThat(tv.itens()).extracting(ProjecaoVista.ItemHipotese::data).containsExactlyElementsOf(real.stream().map(CreditoVisto.ParcelaVista::venceEm).toList());
        assertThat(tv.itens()).extracting(ProjecaoVista.ItemHipotese::valor).containsExactlyElementsOf(
                real.stream().map(CreditoVisto.ParcelaVista::valorDaParcela).toList());
        assertThat(tv.itens().get(0).destino()).startsWith("Fatura Nubank ").endsWith("parcela 1/10");
        // Cada parcela entra no mês em que a fatura vence: os meses somam 3.000,00.
        List<String> faturas = new ArrayList<>();
        for (CreditoVisto.ParcelaVista parcela : real) {
            faturas.add(YearMonth.from(LocalDate.parse(parcela.venceEm())).toString());
        }
        assertThat(parcelado.meses()).filteredOn(m -> faturas.contains(m.mes())).extracting(MesVisto::faturas).containsOnly("300.00");
        assertThat(parcelado.meses()).filteredOn(m -> !faturas.contains(m.mes())).extracting(MesVisto::faturas).containsOnly("0.00");

        CenarioVisto avista = p.cenarios().get(0);
        assertThat(avista.meses().get(0).saidasConhecidas()).isEqualTo("3000.00");
        assertThat(avista.diferencaLinhaBase().saldoFinalP50()).isEqualTo("-3000.00");
        assertThat(parcelado.diferencaLinhaBase().saldoFinalP80()).isEqualTo("-3000.00");
        assertThat(avista.diferencaLinhaBase().menorSaldoP80()).isEqualTo("-3000.00");
        assertThat(avista.diferencaLinhaBase().meses().get(0).saldoFinalP50()).isEqualTo("-3000.00");
        // No parcelado, o menor saldo cai menos que à vista até a última parcela.
        assertThat(parcelado.diferencaLinhaBase().meses().get(0).saldoFinalP50()).isNotEqualTo("-3000.00");

        // Nada foi gravado: nem a compra, nem as faturas.
        assertThat(banco.fetchValue("select count(*) from fatura where dono_id = ?", dono)).isEqualTo(0L);
    }

    @Test
    void rendaCategoriaECompromissoMudamSoOCenario() {
        cenario();
        ProjecaoVista p = projetar.executar(chamador, new Projetar.Entrada(3, List.of(new Projetar.Cenario(null, List.of(
                hipotese("mudanca_categoria", null, null, "Mercado", "-50", mes.plusMonths(1).toString()),
                hipotese("mudanca_renda", "1000.00", null, null, null, null),
                new Projetar.Hipotese("novo_compromisso", "200.00", null, null, null, "saida", null, null,
                        mes.plusMonths(1).atDay(10).toString(), null, null, null, null)))))).projecao();

        CenarioVisto c = p.cenarios().get(0);
        assertThat(c.rotulo()).isEqualTo("Cenário 1");
        assertThat(c.hipoteses()).extracting(ProjecaoVista.HipoteseVista::tipo).containsExactly("mudanca_categoria", "mudanca_renda", "novo_compromisso");
        assertThat(c.hipoteses().get(2).itens()).hasSize(2);
        assertThat(c.meses()).extracting(MesVisto::variavelP50).containsExactly("330.00", "275.00", "275.00");
        assertThat(c.meses()).extracting(MesVisto::entradas).containsExactly("300.00", "1000.00", "1000.00");
        assertThat(c.meses()).extracting(MesVisto::saidasConhecidas).containsExactly("0.00", "1200.00", "1200.00");
        // +175,00 de Mercado, +1.000,00 de renda e −200,00 de compromisso por mês.
        assertThat(c.diferencaLinhaBase().meses()).extracting(ProjecaoVista.DiferencaMesVista::saldoFinalP50).containsExactly("0.00", "975.00", "1950.00");
        assertThat(banco.fetchValue("select count(*) from compromisso where dono_id = ?", dono)).isEqualTo(2L);
    }

    @Test
    void semHistoricoNaoHaEstimativaDeGastoVariavel() {
        conta("corrente", "Itaú", "300.00", mes.minusMonths(1).atDay(1).toString());
        conta("categoria", "Lazer", null, null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        gasto("100.00", mes.minusMonths(1).atDay(1), "Lazer", "Pix");

        ProjecaoVista p = projetar.executar(chamador, new Projetar.Entrada(null, null)).projecao();
        assertThat(p.horizonteMeses()).isEqualTo(6);
        assertThat(p.estimativaVariavel()).isFalse();
        assertThat(p.linhaBase().meses()).hasSize(6).allSatisfy(m -> {
            assertThat(m.variavelP50()).isNull();
            assertThat(m.saldoFinalP80()).isEqualTo("200.00");
        });
        assertThat(p.ressalvas().get(0).codigo()).isEqualTo("historico_insuficiente");
        assertThat(p.ressalvas().get(0).mensagem()).contains("não estime esse gasto por conta própria");
        assertThat(p.piso()).isNull();
        assertThat(p.notas()).anyMatch(n -> n.contains("piso_periodo"));

        // A tela é do dono: nem as notas nem as ordens das ressalvas ao assistente aparecem nela.
        ProjecaoRegistrada registrada = projetar.executar(chamador, new Projetar.Entrada(1, null));
        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrada.urlRetrato().substring(registrada.urlRetrato().indexOf("/fin/")),
                null, null, null, null));
        List<String> textos = textosDaTela(tela);
        assertThat(textos).isNotEmpty().noneMatch(t -> t.contains("Repasse") || t.contains("não estime") || t.contains("apresente-os")
                || t.contains("piso_periodo"));
        assertThat(textos).anyMatch(t -> t.contains("não descontam o gasto do dia a dia"));
    }

    /** O texto de cada componente de texto, com as referências resolvidas nos dados da tela. */
    private static List<String> textosDaTela(Vista tela) {
        List<String> textos = new ArrayList<>();
        for (java.util.Map<String, Object> c : tela.componentes()) {
            if (!"texto".equals(c.get("componente"))) {
                continue;
            }
            Object valor = c.get("valor");
            if (valor instanceof java.util.Map<?, ?> ref) {
                Object atual = tela.dados();
                for (String parte : ref.get("caminho").toString().substring(1).split("/")) {
                    atual = atual instanceof java.util.Map<?, ?> m ? m.get(parte) : ((List<?>) atual).get(Integer.parseInt(parte));
                }
                valor = atual;
            }
            textos.add(String.valueOf(valor));
        }
        return textos;
    }

    @Test
    void oRetratoGuardaOResultadoComparaALinhaDeBaseENaoApareceNoHistorico() {
        cenario();
        ProjecaoRegistrada registrada = projetar.executar(chamador, new Projetar.Entrada(2, List.of(new Projetar.Cenario("10x no Pix?",
                List.of(compra("100.00", "Pix", null))))));

        RetratoVisto retrato = (RetratoVisto) resolver.resolver(chamador, registrada.retrato(), true);
        assertThat(retrato.capacidade()).isEqualTo("projetar");
        assertThat(retrato.resultado().get("linha_base").get("meses").get(1).get("saldo_final_p80").asString()).isEqualTo("400.00");
        assertThat(retrato.parametros().get("cenarios").get(0).get("rotulo").asString()).isEqualTo("10x no Pix?");
        assertThat(retrato.comparacao().mudou()).isFalse();
        assertThat(retrato.comparacao().saldosFinais()).hasSize(2);

        // Moradia não tem gasto típico: os 10,00 saem do saldo de partida e de todos os saldos finais.
        gasto("10.00", hoje, "Moradia", "Pix");
        RetratoVisto depois = (RetratoVisto) resolver.resolver(chamador, registrada.retrato(), true);
        assertThat(depois.comparacao().mudou()).isTrue();
        assertThat(depois.comparacao().saldosFinais().get(0).p50().retrato()).isEqualTo("2150.00");
        assertThat(depois.comparacao().saldosFinais().get(1).p80().diferenca()).isEqualTo("-10.00");

        Vista tela = vistas.abrir(chamador, new Vistas.Pedido(registrada.urlRetrato().substring(registrada.urlRetrato().indexOf("/fin/")),
                null, null, null, null));
        assertThat(tela.dados().toString()).contains("2180.00", "10x no Pix?", "Compra de R$ 100,00");

        assertThat(listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, null, null, null)).operacoes())
                .extracting(OperacaoVista::capacidade).doesNotContain("projetar");
    }

    @Test
    void entradasInvalidasVoltamComOCampoDaHipotese() {
        cenario();
        assertThat(erro(() -> projetar.executar(chamador, new Projetar.Entrada(25, null))).campo()).isEqualTo("horizonte_meses");
        Projetar.Cenario um = new Projetar.Cenario("x", List.of(compra("1.00", "Pix", null)));
        assertThat(erro(() -> projetar.executar(chamador, new Projetar.Entrada(null, List.of(um, um, um, um)))).campo()).isEqualTo("cenarios");
        assertThat(erro(() -> projetar.executar(chamador, new Projetar.Entrada(null, List.of(new Projetar.Cenario("x", List.of()))))).camposFaltando())
                .containsExactly("cenarios[0].hipoteses");
        assertThat(erro(() -> um(compra("100.00", null, null))).camposFaltando()).containsExactly("cenarios[0].hipoteses[0].meio_pagamento");
        ErroTipado parcelasNoPix = erro(() -> um(compra("100.00", "Pix", 3)));
        assertThat(parcelasNoPix.campo()).isEqualTo("cenarios[0].hipoteses[0].parcelas");
        assertThat(parcelasNoPix.mensagem()).startsWith("No cenário 'x': ");
        assertThat(erro(() -> um(hipotese("aporte_meta", "1.00", null, null, null, null))).camposFaltando()).containsExactly("cenarios[0].hipoteses[0].meta");
        assertThat(erro(() -> um(hipotese("mudanca_renda", "1.00", null, null, null, mes.plusMonths(9).toString()))).campo())
                .isEqualTo("cenarios[0].hipoteses[0].de");
        assertThat(erro(() -> um(hipotese("mudanca_categoria", null, null, "Mercado", "-120", null))).campo())
                .isEqualTo("cenarios[0].hipoteses[0].percentual");
        assertThat(erro(() -> um(new Projetar.Hipotese("novo_compromisso", "1.00", null, null, null, null, null, null, null, null, null, null, null)))
                .camposFaltando()).containsExactly("cenarios[0].hipoteses[0].direcao", "cenarios[0].hipoteses[0].primeiro_vencimento");
    }

    private void um(Projetar.Hipotese h) {
        projetar.executar(chamador, new Projetar.Entrada(null, List.of(new Projetar.Cenario("x", List.of(h)))));
    }

    private static Projetar.Hipotese compra(String valor, String meio, Integer parcelas) {
        return new Projetar.Hipotese("compra", valor, meio, parcelas, null, null, null, null, null, null, null, null, null);
    }

    private static Projetar.Hipotese hipotese(String tipo, String valor, String meio, String categoria, String percentual, String de) {
        return new Projetar.Hipotese(tipo, valor, meio, null, null, null, null, null, null, null, categoria, percentual, de);
    }

    private static ErroTipado erro(Runnable acao) {
        try {
            acao.run();
        } catch (ErroTipado e) {
            return e;
        }
        throw new AssertionError("Era esperado um erro");
    }

    private void declarar(String saldo) {
        declarar.executar(chamador, new Declarar.Entrada(chave(), null, "piso_periodo", saldo, null, null));
    }

    private void compromisso(String direcao, String tipo, String nome, String valor, LocalDate vencimento) {
        definirCompromisso.executar(chamador, new DefinirCompromisso.Entrada(chave(), null, null, direcao, tipo, nome, null, valor, null, null,
                null, null, vencimento.toString(), null, null, null, null, "Itaú", null, null, null, null));
    }

    private void gasto(String valor, LocalDate data, String categoria, String meio) {
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, "Compra", meio, "conversa", data.toString(), categoria,
                null, null, null, null, null, null, true, null));
    }

    private void cartao(String nome, int fechamento, int vencimento) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", nome, null, null, null, null, "2020-01-01",
                null, new DadosCartao(fechamento, vencimento, null, "Itaú")));
    }

    private void conta(String tipo, String nome, String saldo, String data) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo, data, null));
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }
}
