package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.AnexarComprovante;
import tools.alicerce.financeiro.aplicacao.DefinirAmbito;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.ListarOperacoes;
import tools.alicerce.financeiro.aplicacao.PrestacaoRegistrada;
import tools.alicerce.financeiro.aplicacao.PrestarContas;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.RetratoVisto;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testes de aceite e de segurança: a prestação de contas de um âmbito num link sem login.
 * Setembro de 2026, com o âmbito Família e o Pessoal misturados na mesma nota, para a página provar que só mostra o
 * que é do âmbito, e nunca saldo, conta ou nome de meio de pagamento.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresDeTeste.class, MinioDeTeste.class})
@ExtendWith(OutputCaptureExtension.class)
class PrestacaoContasTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};
    private static final String LIMITE = "limite-do-teste";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int porta;
    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired DefinirAmbito definirAmbito;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired AnexarComprovante anexar;
    @Autowired PrestarContas prestar;
    @Autowired Desfazer desfazer;
    @Autowired ResolverReferencia resolver;
    @Autowired ListarOperacoes listarOperacoes;
    @Autowired Vistas vistas;
    @Autowired DSLContext banco;
    @Autowired PostgreSQLContainer postgres;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID dono;
    private Chamador chamador;
    private int chaves;
    private String nota;
    private String internet;
    private String mercado;

    @BeforeEach
    void preparar() throws Exception {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString()).execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure", "fin:share"));
        conta("corrente", "Itaú", "1000.00");
        conta("categoria", "Mercado", null);
        conta("categoria", "Internet", null);
        conta("fonte_renda", "Salário", null);
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "debito", "Nubank do Gui", null, "Itaú", true, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Chave do Itaú", null, "Itaú", null, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Pessoal", null, true, null, null));
        definirAmbito.executar(chamador, new DefinirAmbito.Entrada(chave(), null, null, "Família", List.of("familia"), null, null, null));

        // Chave única por teste: a nota fiscal não pode se repetir no banco.
        nota = "3526" + String.format("%040d", Math.abs(dono.getMostSignificantBits() % 1_000_000_000_000L));
        internet = gasto("100.00", "Internet da casa da mãe", "Chave do Itaú", "2026-09-03", "Internet", "Família", null, nota, "Vivo").id();
        mercado = gasto("150.00", "Mercado do mês", "Nubank do Gui", "2026-09-05", null, null,
                List.of(new RegistrarGasto.Item("Compras da casa", null, "90.00", "Mercado", "Família"),
                        new RegistrarGasto.Item("Chocolate só meu", null, "60.00", "Mercado", "Pessoal")), null, "Sacolão").id();
        gasto("40.00", "Cinema sozinho", "Nubank do Gui", "2026-09-10", "Mercado", "Pessoal", null, null, null);
        gasto("70.00", "Feira de outubro", "Nubank do Gui", "2026-10-02", "Mercado", "Família", null, null, null);
        registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, "50.00", "Ajuda do irmão", "Itaú", "Salário", "conversa",
                "2026-09-01", "Família", null, null, null, null, true, null));
        enviar(internet, "comprovante");
        enviar(mercado, "foto");
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    @Test
    void aPaginaMostraSoOAmbitoEOPeriodoComOsNumerosDoServidor() throws Exception {
        PrestacaoRegistrada p = prestar("Família", false).valor();

        assertThat(p.urlPublica()).matches("https://app\\.alicerce\\.tools/fin/p/[A-Za-z0-9_-]{43}");
        assertThat(p.retrato()).startsWith("s-");
        assertThat(p.gastos()).isEqualTo("190.00");
        assertThat(p.entradas()).isEqualTo("50.00");
        assertThat(p.quantidadeLancamentos()).isEqualTo(3);
        assertThat(p.quantidadeComprovantes()).isZero();
        assertThat(p.oQueMostra()).contains("sem login", "Nunca mostra saldos");

        JsonNode vista = JSON.readTree(get(rota(p) + "/vista").body());
        JsonNode dados = vista.get("dados").get("prestacao");
        assertThat(dados.get("ambito").asString()).isEqualTo("Família");
        assertThat(dados.get("gastos").asString()).isEqualTo("190.00");
        assertThat(dados.get("lancamentos")).extracting(l -> l.get("descricao").asString())
                .containsExactly("Ajuda do irmão", "Internet da casa da mãe", "Mercado do mês");
        assertThat(dados.get("lancamentos").get(2).get("valor").asString()).isEqualTo("90.00");
        assertThat(dados.get("lancamentos").get(2).get("meio").asString()).isEqualTo("Débito");
        assertThat(dados.get("lancamentos").get(2).get("estabelecimento").asString()).isEqualTo("Sacolão");
        assertThat(dados.get("lancamentos").get(1).get("meio").asString()).isEqualTo("Pix");
        assertThat(dados.get("categorias")).extracting(c -> c.get("nome").asString() + " " + c.get("gastos").asString())
                .containsExactlyInAnyOrder("Internet 100.00", "Mercado 90.00", "Salário 0.00");
        assertThat(dados.has("comprovantes")).isFalse();
        assertThat(dados.has("notas")).isFalse();
        assertThat(vista.get("componentes")).extracting(c -> c.get("componente").asString()).contains("pagina", "totais", "tabela", "grafico");
        semVazamento(vista.toString());
    }

    @Test
    void aCascaEhGenericaSemTerceirosSemInlineESemCache() throws Exception {
        PrestacaoRegistrada p = prestar("Família", false).valor();

        HttpResponse<String> pagina = get(rota(p));
        assertThat(pagina.statusCode()).isEqualTo(200);
        assertThat(pagina.body()).contains("<title>Prestação de contas</title>", "noindex", "/web/renderizador.js", "/web/publico.js")
                .doesNotContain("Família", "190", "Internet", "style=", "<script>", "http://", "https://");
        String csp = pagina.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("default-src 'none'", "script-src 'self'", "frame-ancestors 'none'").doesNotContain("unsafe-inline", "unsafe-eval");
        assertThat(pagina.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(pagina.headers().firstValue("X-Robots-Tag").orElseThrow()).contains("noindex");
        assertThat(pagina.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
        assertThat(get(rota(p) + "/vista").headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(get("/web/publico.js").body()).doesNotContain("Authorization", "localStorage", "sessionStorage", "fin:read");
    }

    @Test
    void comComprovantesEntramSoOsComprovantesEAsChavesNuncaAsFotos() throws Exception {
        PrestacaoRegistrada p = prestar("Família", true).valor();
        assertThat(p.quantidadeComprovantes()).isEqualTo(1);
        assertThat(p.quantidadeNotas()).isEqualTo(1);

        JsonNode dados = JSON.readTree(get(rota(p) + "/vista").body()).get("dados").get("prestacao");
        JsonNode comprovante = dados.get("comprovantes").get(0);
        assertThat(comprovante.get("titulo").asString()).contains("Internet da casa da mãe");
        String url = comprovante.get("url").asString();
        assertThat(url).startsWith(p.urlPublica() + "/n/");
        assertThat(dados.get("notas").get(0).get("chave").asString()).isEqualTo(nota.replaceAll("(\\d{4})(?!$)", "$1 "));

        HttpResponse<byte[]> imagem = http.send(pedido(URI.create(url).getRawPath()).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(imagem.statusCode()).isEqualTo(200);
        assertThat(imagem.body()).isEqualTo(PNG);
        assertThat(imagem.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("sandbox");
        assertThat(imagem.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");

        // A foto do mercado é do mesmo âmbito, mas não é prova: não entra, nem pelo código.
        String foto = texto("select r.codigo from comprovante c join referencia_curta r on r.entidade_id = c.id "
                + "where c.dono_id = ? and c.natureza = 'foto'", dono);
        assertThat(get(rota(p) + "/n/" + foto.substring(2)).statusCode()).isEqualTo(404);

        // Sem incluir_comprovantes, o mesmo comprovante não abre pelo link.
        PrestacaoRegistrada sem = prestar("Família", false).valor();
        assertThat(get(rota(sem) + "/n/" + URI.create(url).getPath().substring(URI.create(url).getPath().lastIndexOf('/') + 1)).statusCode()).isEqualTo(404);
    }

    @Test
    void revogadoDesfeitoVencidoOuInexistenteRespondemIgual() throws Exception {
        PrestacaoRegistrada revogado = prestar("Família", false).valor();
        PrestacaoRegistrada desfeito = prestar("Família", false).valor();
        PrestacaoRegistrada vencido = prestar("Família", false).valor();
        assertThat(get(rota(revogado)).statusCode()).isEqualTo(200);

        ResultadoEscrita<PrestacaoRegistrada> revogacao = prestar.executar(chamador, revogar(revogado.urlPublica()));
        assertThat(revogacao.valor().revogado()).isTrue();
        assertThat(revogacao.valor().urlPublica()).isNull();
        desfazer(operacaoQueCriou(desfeito));
        try (Connection dba = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var comando = dba.prepareStatement("update link_compartilhamento set expira_em = now() - interval '1 minute' where retrato_id = "
                        + "(select entidade_id from referencia_curta where codigo = ?)")) {
            comando.setString(1, vencido.retrato());
            comando.executeUpdate();
        }

        String inexistente = "/fin/p/" + "A".repeat(43);
        for (String rota : List.of(rota(revogado), rota(desfeito), rota(vencido), inexistente, "/fin/p/curto")) {
            HttpResponse<String> pagina = get(rota);
            assertThat(pagina.statusCode()).as(rota).isEqualTo(404);
            assertThat(pagina.body()).contains("Link indisponível").doesNotContain("Família", "190");
            assertThat(get(rota + "/vista").statusCode()).as(rota).isEqualTo(404);
        }
        assertThat(get(rota(revogado) + "/vista").body()).isEqualTo(get(inexistente + "/vista").body());

        // Revogar de novo é recusado; desfazer a revogação faz o link voltar a abrir.
        assertThat(erro(() -> prestar.executar(chamador, revogar(revogado.retrato()))).mensagem()).contains("já está revogado");
        ReversaoRegistrada previa = desfazer.executar(chamador, new Desfazer.Entrada(null, revogacao.operacao().referencia().id(), null, null)).valor();
        assertThat(previa.efeitosExternos()).singleElement().asString().contains("volta a abrir");
        desfazer(revogacao.operacao().referencia().id());
        assertThat(get(rota(revogado)).statusCode()).isEqualTo(200);
    }

    @Test
    void desfazerACriacaoAvisaQueQuemJaAbriuPodeTerGuardado() {
        PrestacaoRegistrada p = prestar("Família", false).valor();
        ReversaoRegistrada previa = desfazer.executar(chamador, new Desfazer.Entrada(null, operacaoQueCriou(p), null, null)).valor();
        assertThat(previa.efeitosExternos()).singleElement().asString().contains("deixa de abrir", "guardado");
    }

    @Test
    void oTokenNuncaFicaNoBancoNemNoLog(CapturedOutput saida) throws Exception {
        PrestacaoRegistrada p = prestar("Família", true).valor();
        String token = token(p);
        get(rota(p));
        get(rota(p) + "/vista");
        prestar.executar(chamador, revogar(p.urlPublica()));
        erro(() -> prestar.executar(chamador, revogar(p.urlPublica())));
        get(rota(p));

        String tudo = texto("""
                select coalesce(string_agg(t, ' '), '') from (
                  select to_jsonb(o)::text as t from operacao o where o.dono_id = ?
                  union all select to_jsonb(r)::text from resultado_operacao r join operacao o on o.id = r.operacao_id where o.dono_id = ?
                  union all select to_jsonb(e)::text from evento_auditoria e where e.dono_id = ?
                  union all select to_jsonb(l)::text from link_compartilhamento l where l.dono_id = ?
                  union all select to_jsonb(x)::text from retrato x where x.dono_id = ?) linhas""", dono, dono, dono, dono, dono);
        assertThat(tudo).contains("prestar_contas").doesNotContain(token);
        assertThat(saida.getAll()).doesNotContain(token);
    }

    @Test
    void linkDeOutroDonoNaoSeRevogaEFaltasVoltamComoCamposFaltando() {
        PrestacaoRegistrada p = prestar("Família", false).valor();
        Chamador outro = new Chamador(UUID.randomUUID(), "claude-ai", Set.of("fin:share"));
        banco.insertInto(DONO).set(DONO.ID, outro.dono()).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, outro.dono().toString()).execute();

        assertThat(erro(() -> prestar.executar(outro, revogar(p.urlPublica()))).codigo()).isEqualTo(CodigoErro.REFERENCIA_INEXISTENTE);
        assertThat(erro(() -> prestar.executar(chamador, new PrestarContas.Entrada(null, null, null, null, "2026-09", null, null, null)))
                .camposFaltando()).containsExactly("ambito");
        assertThat(erro(() -> prestar.executar(chamador, new PrestarContas.Entrada(null, "Família", null, null, null, null, null, null)))
                .camposFaltando()).containsExactly("de", "ate");
        assertThat(erro(() -> prestar.executar(chamador, new PrestarContas.Entrada(null, "Família", null, null, "2026-09", null, 400, null)))
                .campo()).isEqualTo("validade_dias");
        assertThat(erro(() -> prestar.executar(chamador, new PrestarContas.Entrada(null, "Família", null, null, null, null, null, p.urlPublica())))
                .campo()).isEqualTo("revogar");
    }

    @Test
    void oRetratoGuardaOQueOLinkMostraEAComparacaoDizQuandoFicouParaTras() {
        PrestacaoRegistrada p = prestar("Família", false).valor();
        gasto("25.00", "Remédio da mãe", "Nubank do Gui", "2026-09-20", "Mercado", "Família", null, null, null);

        RetratoVisto retrato = (RetratoVisto) resolver.resolver(chamador, p.retrato(), true);
        assertThat(retrato.capacidade()).isEqualTo("prestar_contas");
        assertThat(retrato.comparacao().mudou()).isTrue();
        assertThat(retrato.comparacao().saidas().diferenca()).isEqualTo("25.00");

        Vista doDono = vistas.abrir(chamador, new Vistas.Pedido("/fin/s/" + p.retrato().substring(2), null, null, null, null));
        assertThat(JSON.writeValueAsString(doDono.dados())).contains("190.00").doesNotContain("Remédio da mãe");
        assertThat(listarOperacoes.executar(chamador, new ListarOperacoes.Entrada(null, null, null, null, "prestar_contas", null, null)).operacoes())
                .singleElement().satisfies(o -> assertThat(o.resumo()).isEqualTo("prestação de contas de Família " + p.retrato()));
    }

    /** O que a página nunca pode trazer: saldo, conta, nome de meio, outro âmbito, gasto pessoal ou id interno. */
    private static void semVazamento(String texto) {
        assertThat(texto).doesNotContain("Itaú", "Nubank do Gui", "Chave do Itaú", "Pessoal", "Cinema", "Chocolate", "Feira de outubro", "1000.00",
                "\"t-", "\"a-", "\"o-");
        assertThat(texto).doesNotContainPattern("\"saldo[a-z_]*\"");
    }

    private ResultadoEscrita<PrestacaoRegistrada> prestar(String ambito, boolean comprovantes) {
        return prestar.executar(chamador, new PrestarContas.Entrada(null, ambito, null, null, "2026-09", comprovantes, null, null));
    }

    private static PrestarContas.Entrada revogar(String alvo) {
        return new PrestarContas.Entrada(null, null, null, null, null, null, null, alvo);
    }

    private String operacaoQueCriou(PrestacaoRegistrada p) {
        return texto("select r.codigo from link_compartilhamento l join referencia_curta r on r.entidade_id = l.operacao_id "
                + "join referencia_curta s on s.entidade_id = l.retrato_id where s.codigo = ?", p.retrato());
    }

    private String texto(String sql, Object... valores) {
        return String.valueOf(banco.fetchValue(sql, valores));
    }

    private void desfazer(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private void conta(String tipo, String nome, String saldo) {
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, tipo, nome, null, null, null, saldo,
                saldo == null ? null : "2020-01-01", null));
    }

    private LancamentoRegistrado gasto(String valor, String descricao, String meio, String data, String categoria, String ambito,
            List<RegistrarGasto.Item> itens, String chaveNfce, String contraparte) {
        return registrarGasto.executar(chamador, new RegistrarGasto.Entrada(chave(), null, valor, descricao, meio, "conversa", data, categoria,
                ambito, itens, null, chaveNfce, contraparte, null, true, null)).valor();
    }

    /** Pela página de envio, como o dono faria. */
    private void enviar(String lancamento, String natureza) throws Exception {
        String url = anexar.executar(chamador, new AnexarComprovante.Entrada(null, null, lancamento, null, null)).valor().urlAnexo();
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        corpo.write(("--" + LIMITE + "\r\nContent-Disposition: form-data; name=\"natureza\"\r\n\r\n" + natureza + "\r\n").getBytes(StandardCharsets.UTF_8));
        corpo.write(("--" + LIMITE + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        corpo.write(PNG);
        corpo.write(("\r\n--" + LIMITE + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resposta = http.send(pedido(URI.create(url).getRawPath()).header("Content-Type", "multipart/form-data; boundary=" + LIMITE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(201);
    }

    private static String token(PrestacaoRegistrada p) {
        return p.urlPublica().substring(p.urlPublica().lastIndexOf('/') + 1);
    }

    private static String rota(PrestacaoRegistrada p) {
        return "/fin/p/" + token(p);
    }

    private String chave() {
        return "chave-" + dono + "-" + chaves++;
    }

    private static ErroTipado erro(Runnable acao) {
        return catchThrowableOfType(ErroTipado.class, acao::run);
    }

    private HttpRequest.Builder pedido(String rota) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + rota));
    }

    private HttpResponse<String> get(String rota) throws Exception {
        return http.send(pedido(rota).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
