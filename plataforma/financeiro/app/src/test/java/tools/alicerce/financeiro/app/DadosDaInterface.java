package tools.alicerce.financeiro.app;

import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.financeiro.aplicacao.ContaDefinida;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirConta.DadosCartao;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.EntradaMoeda;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.ObterContexto;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.RegistrarTransferencia;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ResumirPeriodo;
import tools.jackson.databind.json.JsonMapper;

/** Uma conta completa, com um endereço de cada tipo, para os testes da interface. */
@Component
class DadosDaInterface {

    /**
     * Os endereços de uma conta de teste.
     *
     * @param emMoedaEstrangeira a conta e o câmbio, à parte dos que todo teste percorre
     */
    record Enderecos(Chamador chamador, Map<String, String> caminhos, Map<String, String> emMoedaEstrangeira) {

        Enderecos(Chamador chamador, Map<String, String> caminhos) {
            this(chamador, caminhos, Map.of());
        }
    }

    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarTransferencia transferir;
    @Autowired ResumirPeriodo resumirPeriodo;
    @Autowired tools.alicerce.financeiro.aplicacao.RitmoGastos ritmoGastos;
    @Autowired tools.alicerce.financeiro.aplicacao.CortesNecessarios cortesNecessarios;
    @Autowired tools.alicerce.financeiro.aplicacao.Projetar projetar;
    @Autowired tools.alicerce.financeiro.aplicacao.PrestarContas prestarContas;
    @Autowired ObterContexto obterContexto;
    @Autowired ResolverReferencia resolverReferencia;
    @Autowired tools.alicerce.financeiro.aplicacao.DefinirCompromisso definirCompromisso;
    @Autowired tools.alicerce.financeiro.aplicacao.ConferirSaldo conferirSaldo;
    @Autowired tools.alicerce.financeiro.aplicacao.CorrigirLancamento corrigirLancamento;
    @Autowired tools.alicerce.financeiro.aplicacao.DefinirMeta definirMeta;
    @Autowired DSLContext banco;

    Chamador novoDono() {
        UUID dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        return new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
    }

    /** O dono que a segurança já resolveu para o token. */
    Chamador donoDoToken(String token) {
        String carga = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        String sujeito = JsonMapper.builder().build().readTree(carga).get("sub").asString();
        UUID dono = banco.select(DONO.ID).from(DONO).where(DONO.SUJEITO_OIDC.eq(sujeito)).fetchOne(DONO.ID);
        return new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
    }

    Enderecos preparar(Chamador chamador) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "corrente", "Itaú", null, null, null, "1000.00", "2020-01-01", null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "categoria", "Mercado", null, null, null, null, null, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", true, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, true, null, null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "cartao_credito", "Nubank", null, null, null, null, "2020-01-01", null,
                new DadosCartao(10, 17, "5000.00", "Itaú")));

        var gasto = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "123.45", "Feira da semana", "Pix", "conversa",
                "2026-10-05", "Mercado", null, null, null, null, "Sacolão", null, true, null));
        var compra = registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "60.00", "Tênis", "Nubank", "conversa",
                "2026-10-06", "Mercado", null, null, null, null, null, null, true, null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "corrente", "Wise", null, null, null, "100.00", "2020-01-01", null, null, "USD"));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "corrente", "Revolut", null, null, null, "50.00", "2020-01-01", null, null, "EUR"));
        var cambio = transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "550.00", "Itaú", "Wise", "2026-10-08", null, null, null, true, null,
                new EntradaMoeda(null, null, "5.50", null)));
        var resumo = resumirPeriodo.executar(chamador, new ResumirPeriodo.Entrada("2026-10-01", "2026-10-31", null, List.of("categoria"), null, null, null, null));
        // Compromisso com a primeira parcela paga em setembro, fora do mês do relatório, e uma conferência pendente no Itaú.
        var compromisso = definirCompromisso.executar(chamador, new tools.alicerce.financeiro.aplicacao.DefinirCompromisso.Entrada(chave(), null, null,
                "saida", "parcelado", "Parcelamento IPTU", null, "150.00", null, 4, null, null, "2026-09-10", null, "Prefeitura", "PARC-0042", "Mercado",
                "Itaú", null, null, null, null)).valor();
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "155.00", "IPTU parcela 1", "Pix", "conversa", "2026-09-10",
                "Mercado", null, null, null, null, null, null, true, null, null, null, null, null, null, null, compromisso.id(), "1"));
        // A parcela 2 paga só em parte, também em setembro: a tela do compromisso e a agenda mostram o que falta.
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "100.00", "IPTU parcela 2, parte", "Pix", "conversa", "2026-09-25",
                "Mercado", null, null, null, null, null, null, true, null, null, null, null, null, null, null, compromisso.id(), "2"));
        conferirSaldo.executar(chamador, new tools.alicerce.financeiro.aplicacao.ConferirSaldo.Entrada(chave(), null, "Itaú", null, "1.00", null, null, null));
        // Outubro contra os seis meses antes, todos depois do marco: com típico (zero, sem os fixos de setembro) e tendência.
        var ritmo = ritmoGastos.executar(chamador, new tools.alicerce.financeiro.aplicacao.RitmoGastos.Entrada("2026-10", null, null, null));
        // Cortes do mês de hoje para um saldo avulso de 777,00: o alvo é o único número que não depende da data do teste.
        var cortes = cortesNecessarios.executar(chamador, new tools.alicerce.financeiro.aplicacao.CortesNecessarios.Entrada(null, "777.00", null, null));
        // Projeção de seis meses com uma compra de 654,32 no Pix: o valor da hipótese também não depende da data.
        var projecao = projetar.executar(chamador, new tools.alicerce.financeiro.aplicacao.Projetar.Entrada(null, List.of(
                new tools.alicerce.financeiro.aplicacao.Projetar.Cenario("Notebook no Pix", List.of(new tools.alicerce.financeiro.aplicacao.Projetar.Hipotese(
                        "compra", "654.32", "Pix", null, null, null, null, null, null, null, null, null, null))))));

        // A prestação de contas do âmbito Pessoal em outubro: a tela do dono é a mesma que a família vê pelo link.
        var prestacao = prestarContas.executar(chamador, new tools.alicerce.financeiro.aplicacao.PrestarContas.Entrada(null, "Pessoal", null, null,
                "2026-10", true, null, null)).valor();

        // Uma meta com conta reservada e um aporte de 250,00 nela, hoje: depois da projeção, que fica sem metas.
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "poupanca", "Poupança da viagem", null, null, null, null, null, null));
        var meta = definirMeta.executar(chamador, new tools.alicerce.financeiro.aplicacao.DefinirMeta.Entrada(chave(), null, null, "Viagem 2027", null,
                "6000.00", null, 2, "Poupança da viagem", null, null)).valor();
        transferir.executar(chamador, new RegistrarTransferencia.Entrada(chave(), null, "250.00", "Itaú", "Poupança da viagem", null, null, null, null,
                true, null, null, "Viagem 2027", null));

        gastoRecusado(chamador);

        var contexto = obterContexto.executar(chamador);
        String conta = contexto.contas().stream().filter(c -> c.nome().equals("Itaú")).findFirst().orElseThrow().id();
        String fatura = resolverReferencia.executar(chamador, compra.valor().id()).movimentos().stream()
                .map(m -> m.fatura()).filter(f -> f != null).findFirst().orElseThrow();
        String wise = contexto.contas().stream().filter(c -> c.nome().equals("Wise")).findFirst().orElseThrow().id();
        return new Enderecos(chamador, Map.ofEntries(
                Map.entry("operacoes", "/fin/operacoes"),
                Map.entry("contas", "/fin/contas"),
                Map.entry("lancamentos", "/fin/lancamentos"),
                Map.entry("lancamento", "/fin/t/" + gasto.valor().id().substring(2)),
                Map.entry("conta", "/fin/a/" + conta.substring(2)),
                Map.entry("fatura", "/fin/f/" + fatura.substring(2)),
                Map.entry("operacao", "/fin/o/" + gasto.operacao().referencia().codigo()),
                Map.entry("retrato", "/fin/s/" + resumo.retrato().substring(2)),
                Map.entry("ritmo", "/fin/s/" + ritmo.retrato().substring(2)),
                Map.entry("cortes", "/fin/s/" + cortes.retrato().substring(2)),
                Map.entry("projecao", "/fin/s/" + projecao.retrato().substring(2)),
                Map.entry("prestacao", "/fin/s/" + prestacao.retrato().substring(2)),
                Map.entry("relatorio", "/fin/relatorios?de=2026-10-01&ate=2026-10-31"),
                Map.entry("agenda", "/fin/agenda?ate=2026-12-31"),
                Map.entry("compromissos", "/fin/compromissos"),
                Map.entry("compromisso", "/fin/k/" + compromisso.id().substring(2)),
                Map.entry("meta", "/fin/m/" + meta.id().substring(2))),
                Map.of("conta", "/fin/a/" + wise.substring(2), "cambio", "/fin/t/" + cambio.valor().id().substring(2)));
    }

    /** Um gasto novo, para cada teste ter o que desfazer sem mudar a massa dos outros. */
    ResultadoEscrita<LancamentoRegistrado> gasto(Chamador chamador, String descricao) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "10.00", descricao, "Pix", "conversa",
                "2026-10-07", "Mercado", null, null, null, null, null, null, true, null));
    }

    /** Um gasto sem valor: o registrador o recusa com campos_faltando, e a trilha de auditoria o mostra destacado. */
    void gastoRecusado(Chamador chamador) {
        try {
            registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, null, "Gasto sem valor", "Pix", "conversa",
                    "2026-10-07", "Mercado", null, null, null, null, null, null, true, null));
            throw new IllegalStateException("O gasto sem valor deveria ser recusado");
        } catch (tools.alicerce.convencoes.ErroTipado esperado) {
            // Recusado, como deve ser.
        }
    }

    /** Um gasto registrado por foto da nota: a resposta traz o link de envio do comprovante. */
    ResultadoEscrita<LancamentoRegistrado> gastoComFoto(Chamador chamador, String descricao) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "10.00", descricao, "Pix", "foto_nota",
                "2026-10-07", "Mercado", null, null, null, null, null, null, true, null));
    }

    /** Anula o lançamento: a tela dele não oferece mais o envio de imagem. */
    void anular(Chamador chamador, String lancamento) {
        corrigirLancamento.executar(chamador, new tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Entrada(chave(), null, lancamento, "anular", null, "teste", null));
    }

    /** Uma categoria nova, que depois recebe um gasto: desfazer a criação dela fica bloqueado. */
    ResultadoEscrita<ContaDefinida> categoriaComGasto(Chamador chamador, String nome) {
        var categoria = definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "categoria", nome, null, null, null, null, null, null));
        registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, "5.00", "Gasto em " + nome, "Pix", "conversa",
                "2026-10-07", nome, null, null, null, null, null, null, true, null));
        return categoria;
    }

    ResultadoEscrita<ContaDefinida> categoria(Chamador chamador, String nome) {
        return definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "categoria", nome, null, null, null, null, null, null));
    }

    private static String chave() {
        return UUID.randomUUID().toString();
    }
}
