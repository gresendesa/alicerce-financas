package tools.alicerce.financeiro.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.COMPROVANTE;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.DONO;
import static tools.alicerce.financeiro.persistencia.jooq.Tables.TOKEN_ANEXO;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.S3Client;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.AnexarComprovante;
import tools.alicerce.financeiro.aplicacao.AnexoRegistrado;
import tools.alicerce.financeiro.aplicacao.BaixadorArquivos;
import tools.alicerce.financeiro.aplicacao.ComprovanteVisto;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento;
import tools.alicerce.financeiro.aplicacao.CorrigirLancamento.Alteracoes;
import tools.alicerce.financeiro.aplicacao.DefinirConta;
import tools.alicerce.financeiro.aplicacao.DefinirMeioPagamento;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.LancamentoRegistrado;
import tools.alicerce.financeiro.aplicacao.LancamentoVisto;
import tools.alicerce.financeiro.aplicacao.RegistrarGanho;
import tools.alicerce.financeiro.aplicacao.RegistrarGasto;
import tools.alicerce.financeiro.aplicacao.ResolverReferencia;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;
import tools.alicerce.financeiro.aplicacao.ResultadoEscrita;
import tools.alicerce.financeiro.aplicacao.ReversaoRegistrada;
import tools.alicerce.financeiro.dominio.TipoImagem;

/** Testes de aceite: banco e bucket reais, página e link por HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "alicerce.financeiro.comprovantes.hosts-download=arquivos.exemplo.test")
@Import({PostgresDeTeste.class, MinioDeTeste.class, ComprovantesTest.Baixador.class})
class ComprovantesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4};
    private static final String LIMITE = "limite-do-teste";

    /** O que o cliente "hospeda": o download real tem teste próprio, em BaixadorHttpTest. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Baixador {
        static volatile Optional<byte[]> resposta = Optional.of(PNG);
        static volatile int chamadas;

        @Bean
        @Primary
        BaixadorArquivos baixadorDeTeste() {
            return (endereco, limite) -> {
                chamadas++;
                return resposta;
            };
        }
    }

    @LocalServerPort int porta;
    @Autowired DefinirConta definirConta;
    @Autowired DefinirMeioPagamento definirMeio;
    @Autowired RegistrarGasto registrarGasto;
    @Autowired RegistrarGanho registrarGanho;
    @Autowired CorrigirLancamento corrigir;
    @Autowired AnexarComprovante anexar;
    @Autowired ResolverReferencia resolver;
    @Autowired Vistas vistas;
    @Autowired Desfazer desfazer;
    @Autowired DSLContext banco;
    @Autowired S3Client s3;
    @Autowired PostgreSQLContainer postgres;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID dono;
    private Chamador chamador;
    private int chaves;

    @BeforeEach
    void preparar() {
        dono = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, dono).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, dono.toString())
                .execute();
        chamador = new Chamador(dono, "claude-ai", Set.of("fin:read", "fin:write", "fin:structure"));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "corrente", "Itaú", null, null, null, "1000.00", "2020-01-01", null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "categoria", "Mercado", null, null, null, null, null, null));
        definirConta.executar(chamador, new DefinirConta.Entrada(chave(), null, null, "fonte_renda", "Salário", null, null, null, null, null, null));
        definirMeio.executar(chamador, new DefinirMeioPagamento.Entrada(chave(), null, null, "pix", "Pix", null, "Itaú", null, null));
        Baixador.resposta = Optional.of(PNG);
        Baixador.chamadas = 0;
    }

    @AfterEach
    void invariantes() {
        assertThat(EscritasRegistradas.semEfeito(banco, dono)).isEmpty();
    }

    // Link de envio

    @Test
    void gastoDeImagemTrazLinkDeUsoUnicoEOTokenNaoFicaNoBanco() throws Exception {
        LancamentoRegistrado gasto = gasto("foto_nota", chave()).valor();

        assertThat(gasto.urlAnexo()).startsWith("https://app.alicerce.tools/fin/anexo/");
        assertThat(gasto.urlAnexoValidaAte()).isNotBlank();
        String token = token(gasto.urlAnexo());
        String tudo = banco.fetch("""
                select coalesce(string_agg(t, ' '), '') from (
                  select o.argumentos::text || r.resultado::text as t from operacao o join resultado_operacao r on r.operacao_id = o.id
                  where o.dono_id = ? union all select hash_token from token_anexo where dono_id = ?) x""", dono, dono)
                .get(0).get(0, String.class);
        assertThat(tudo).doesNotContain(token);
        assertThat(banco.fetchCount(TOKEN_ANEXO, TOKEN_ANEXO.DONO_ID.eq(dono))).isEqualTo(1);
    }

    @Test
    void conversaESimulacaoNaoGeramLinkERepeticaoVoltaSemEle() {
        assertThat(gasto("conversa", chave()).valor().urlAnexo()).isNull();
        LancamentoRegistrado simulado = registrarGasto.executar(chamador, entrada("foto_nota", null, true)).valor();
        assertThat(simulado.urlAnexo()).isNull();
        assertThat(banco.fetchCount(TOKEN_ANEXO, TOKEN_ANEXO.DONO_ID.eq(dono))).isZero();

        String chave = chave();
        gasto("print_app", chave);
        ResultadoEscrita<LancamentoRegistrado> repetido = registrarGasto.executar(chamador, entrada("print_app", chave, null));
        assertThat(repetido.repetido()).isTrue();
        assertThat(repetido.valor().urlAnexo()).isNull();
        assertThat(repetido.valor().comprovantePendente()).isTrue();
    }

    @Test
    void ganhoDeImagemTambemTrazLink() {
        LancamentoRegistrado ganho = registrarGanho.executar(chamador, new RegistrarGanho.Entrada(chave(), null, "500.00", "Pix",
                "Itaú", "Salário", "print_app", null, null, null, null, null, null, null, null)).valor();
        assertThat(ganho.urlAnexo()).contains("/fin/anexo/");
    }

    @Test
    void paginaAceitaUmaImagemUmaVezEOComprovanteAbrePorLinkTemporario() throws Exception {
        LancamentoRegistrado gasto = gasto("foto_nota", chave()).valor();
        String rota = "/fin/anexo/" + token(gasto.urlAnexo());

        // Prévias de link abrem a página: isso não gasta o token.
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> pagina = get(rota);
            assertThat(pagina.statusCode()).isEqualTo(200);
            assertThat(pagina.body()).contains("noindex").doesNotContain(gasto.descricao(), "<script");
            semIndexacao(pagina);
            assertThat(pagina.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("default-src 'none'");
        }

        HttpResponse<String> envio = enviar(rota, PNG);
        assertThat(envio.statusCode()).as(envio.body()).isEqualTo(201);
        semIndexacao(envio);
        assertThat(enviar(rota, PNG).statusCode()).isEqualTo(410);
        assertThat(get(rota).statusCode()).isEqualTo(410);

        LancamentoVisto visto = resolver.executar(chamador, gasto.id());
        assertThat(visto.comprovantes()).hasSize(1);
        ComprovanteVisto comprovante = visto.comprovantes().get(0);
        assertThat(comprovante.id()).startsWith("n-");
        assertThat(comprovante.tipoMidia()).isEqualTo("image/png");
        assertThat(comprovante.tamanhoBytes()).isEqualTo(PNG.length);
        assertThat(comprovante.lancamento()).isEqualTo(gasto.id());
        assertThat(comprovante.urlTemporaria()).doesNotContain("amazonaws", MinioDeTeste.BUCKET);

        ComprovanteVisto porId = (ComprovanteVisto) resolver.resolver(chamador, "/fin/n/" + comprovante.id().substring(2));
        HttpResponse<byte[]> imagem = http.send(pedido(caminho(porId.urlTemporaria())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(imagem.statusCode()).isEqualTo(200);
        assertThat(imagem.body()).isEqualTo(PNG);
        assertThat(imagem.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/png");
        assertThat(imagem.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        assertThat(imagem.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(imagem.headers().firstValue("X-Robots-Tag").orElseThrow()).contains("noimageindex");
        assertThat(s3.headObject(h -> h.bucket(MinioDeTeste.BUCKET).key(chaveNoBucket())).contentLength()).isEqualTo(PNG.length);
        assertThat(chaveNoBucket()).startsWith("comprovantes/" + dono + "/"); // um bucket só, um diretório por dono

        String adulterada = caminho(porId.urlTemporaria()) + "x";
        assertThat(get(adulterada).statusCode()).isEqualTo(410);
    }

    @Test
    void tipoForaDaListaETamanhoAcimaDoLimiteSaoRecusadosSemGastarOToken() throws Exception {
        String rota = "/fin/anexo/" + token(gasto("foto_nota", chave()).valor().urlAnexo());

        assertThat(enviar(rota, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(422);
        assertThat(enviar(rota, new byte[0]).statusCode()).isIn(400, 422);
        byte[] gigante = Arrays.copyOf(PNG, TipoImagem.TAMANHO_MAXIMO + 1);
        assertThat(enviar(rota, gigante).statusCode()).isEqualTo(413);
        assertThat(banco.fetchCount(COMPROVANTE, COMPROVANTE.DONO_ID.eq(dono))).isZero();

        byte[] noLimite = Arrays.copyOf(PNG, TipoImagem.TAMANHO_MAXIMO);
        assertThat(enviar(rota, noLimite).statusCode()).isEqualTo(201);
    }

    @Test
    void linkVencidoERecusado() throws Exception {
        String rota = "/fin/anexo/" + token(gasto("foto_nota", chave()).valor().urlAnexo());
        // financeiro_app não muda a validade de um token: só o dono do esquema, aqui.
        try (Connection dba = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var comando = dba.prepareStatement("update token_anexo set expira_em = now() - interval '1 minute' where dono_id = ?")) {
            comando.setObject(1, dono);
            comando.executeUpdate();
        }

        assertThat(get(rota).statusCode()).isEqualTo(410);
        assertThat(enviar(rota, PNG).statusCode()).isEqualTo(410);
    }

    @Test
    void tokenDesconhecidoELancamentoAnuladoNaoAceitamEnvio() throws Exception {
        assertThat(get("/fin/anexo/inexistente").statusCode()).isEqualTo(410);

        LancamentoRegistrado gasto = gasto("foto_nota", chave()).valor();
        corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, gasto.id(), "anular", null, "errado", null));
        assertThat(enviar("/fin/anexo/" + token(gasto.urlAnexo()), PNG).statusCode()).isEqualTo(410);
        assertThat(banco.fetchCount(COMPROVANTE, COMPROVANTE.DONO_ID.eq(dono))).isZero();
    }

    @Test
    void lancamentoAntigoGanhaLinkPeloAssistente() throws Exception {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();

        ResultadoEscrita<AnexoRegistrado> link = anexar.executar(chamador, new AnexarComprovante.Entrada(null, null, gasto.id(), null, null));

        assertThat(link.valor().urlAnexo()).contains("/fin/anexo/");
        assertThat(link.valor().comprovante()).isNull();
        assertThat(enviar("/fin/anexo/" + token(link.valor().urlAnexo()), PNG).statusCode()).isEqualTo(201);
        assertThat(banco.fetch("select r.resultado::text from resultado_operacao r where r.operacao_id = ?", link.operacao().id())
                .get(0).get(0, String.class)).doesNotContain(token(link.valor().urlAnexo()));
    }

    // Natureza da imagem

    @Test
    void aPaginaPerguntaANaturezaEAFotoFicaSeparadaDaNota() throws Exception {
        LancamentoRegistrado gasto = gasto("foto_nota", chave()).valor();
        String rota = "/fin/anexo/" + token(gasto.urlAnexo());
        String formulario = get(rota).body();
        assertThat(formulario).contains("name=\"natureza\" value=\"comprovante\" checked", "name=\"natureza\" value=\"foto\"",
                "Foto do que foi comprado").doesNotContain(gasto.id(), "Voltar ao lançamento");

        assertThat(enviar(rota, PNG, "selfie").statusCode()).isEqualTo(422); // não gasta o token
        HttpResponse<String> foto = enviar(rota, PNG, "foto");

        assertThat(foto.statusCode()).as(foto.body()).isEqualTo(201);
        assertThat(foto.body()).contains("Foto recebida", "Voltar ao lançamento", "href=\"" + gasto.url() + "\"");
        String outro = anexar.executar(chamador, new AnexarComprovante.Entrada(null, null, gasto.id(), null, null)).valor().urlAnexo();
        HttpResponse<String> nota = enviar("/fin/anexo/" + token(outro), PNG);
        assertThat(nota.body()).contains("Comprovante recebido", "Voltar ao lançamento");

        LancamentoVisto visto = resolver.executar(chamador, gasto.id());
        assertThat(visto.comprovantes()).singleElement().extracting(ComprovanteVisto::natureza).isEqualTo("comprovante");
        assertThat(visto.fotos()).singleElement().extracting(ComprovanteVisto::natureza).isEqualTo("foto");
        Vista tela = vistas.abrir(chamador, new Vistas.Pedido("/fin/t/" + gasto.id().substring(2), null, null, null, null));
        assertThat(tela.componentes()).extracting(c -> c.get("rotulo")).contains("Comprovantes", "Fotos");
        assertThat(tela.dados().toString()).contains("anexavel=true");
        Vista daFoto = vistas.abrir(chamador, new Vistas.Pedido("/fin/n/" + visto.fotos().get(0).id().substring(2), null, null, null, null));
        assertThat(daFoto.toString()).contains("Foto do que foi comprado", "não é prova");
        assertThat(banco.fetch("select r.resultado::text from operacao o join resultado_operacao r on r.operacao_id = o.id "
                + "where o.dono_id = ? and o.capacidade = 'enviar_comprovante' order by o.criada_em", dono).getValues(0, String.class))
                .anySatisfy(r -> assertThat(r).contains("\"natureza\": \"foto\"")).anySatisfy(r -> assertThat(r).contains("\"natureza\": \"comprovante\""));
    }

    @Test
    void recebimentoDiretoAceitaANaturezaERecusaOutra() {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();

        AnexoRegistrado foto = anexar.executar(chamador, new AnexarComprovante.Entrada(chave(), null, gasto.id(),
                new AnexarComprovante.ArquivoRecebido("https://arquivos.exemplo.test/f", "file_1"), "Foto")).valor();
        AnexoRegistrado padrao = anexar.executar(chamador, direto(gasto.id(), "https://arquivos.exemplo.test/f", chave())).valor();

        assertThat(foto.natureza()).isEqualTo("foto");
        assertThat(padrao.natureza()).isEqualTo("comprovante");
        assertThat(resolver.executar(chamador, gasto.id()).fotos()).hasSize(1);
        ErroTipado erro = erro(() -> anexar.executar(chamador, new AnexarComprovante.Entrada(chave(), null, gasto.id(),
                new AnexarComprovante.ArquivoRecebido("https://arquivos.exemplo.test/f", "file_1"), "selfie")));
        assertThat(erro.codigo()).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
        assertThat(erro.campo()).isEqualTo("natureza");
    }

    @Test
    void imagemGuardadaAntesDaNaturezaContinuaComprovante() throws Exception {
        // Como uma linha da V6: o INSERT não conhece a coluna, e o default a preenche. Fica num dono à parte,
        // porque não nasce de uma operação.
        UUID id = UUID.randomUUID();
        UUID antigo = UUID.randomUUID();
        banco.insertInto(DONO).set(DONO.ID, antigo).set(DONO.EMISSOR_OIDC, "https://teste").set(DONO.SUJEITO_OIDC, antigo.toString()).execute();
        try (Connection dba = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var comando = dba.prepareStatement("""
                        insert into comprovante (id, dono_id, tipo_midia, tamanho_bytes, chave_armazenamento, sha256)
                        values (?, ?, 'image/png', 12, 'comprovantes/antigo', repeat('a', 64))""")) {
            comando.setObject(1, id);
            comando.setObject(2, antigo);
            comando.executeUpdate();
        }

        assertThat(banco.fetchOne("select natureza from comprovante where id = ?", id).get(0, String.class)).isEqualTo("comprovante");
    }

    @Test
    void osDadosDeOutraRotaFinContinuamExigindoToken() throws Exception {
        assertThat(get("/api/fin/vista?caminho=/fin/t/8K3QZ2").statusCode()).isEqualTo(401);
    }

    // Recebimento direto

    @Test
    void recebimentoDiretoGuardaEVinculaSemGravarOEndereco() {
        LancamentoRegistrado gasto = gasto("foto_nota", chave()).valor();
        String chave = chave();
        String url = "https://cdn.arquivos.exemplo.test/f?sig=segredo";

        ResultadoEscrita<AnexoRegistrado> anexo = anexar.executar(chamador, direto(gasto.id(), url, chave));

        assertThat(anexo.valor().comprovante()).startsWith("n-");
        assertThat(resolver.executar(chamador, gasto.id()).comprovantes()).hasSize(1);
        assertThat(banco.fetch("select argumentos::text from operacao where id = ?", anexo.operacao().id())
                .get(0).get(0, String.class)).contains("file_1").doesNotContain("segredo");

        ResultadoEscrita<AnexoRegistrado> repetido = anexar.executar(chamador, direto(gasto.id(), url, chave));
        assertThat(repetido.repetido()).isTrue();
        assertThat(resolver.executar(chamador, gasto.id()).comprovantes()).hasSize(1);
    }

    @Test
    void downloadSoDeHostPermitidoPorHttps() {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();
        for (String url : List.of("http://arquivos.exemplo.test/f", "https://169.254.169.254/latest/meta-data",
                "https://arquivos.exemplo.test.mal.test/f", "https://malarquivos.exemplo.test/f",
                "https://arquivos.exemplo.test:8443/f", "https://u@arquivos.exemplo.test/f", "file:///etc/passwd", "nao e url")) {
            ErroTipado erro = erro(() -> anexar.executar(chamador, direto(gasto.id(), url, chave())));
            assertThat(erro.codigo()).as(url).isEqualTo(CodigoErro.VALIDACAO_FALHOU);
            assertThat(erro.campo()).isEqualTo("arquivo");
        }
        assertThat(Baixador.chamadas).isZero();
    }

    @Test
    void downloadAcimaDoLimiteOuQueNaoEImagemERecusado() {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();
        Baixador.resposta = Optional.empty();
        assertThat(erro(() -> anexar.executar(chamador, direto(gasto.id(), "https://arquivos.exemplo.test/f", chave()))).mensagem())
                .contains("10 MB");
        Baixador.resposta = Optional.of("texto".getBytes(StandardCharsets.UTF_8));
        assertThat(erro(() -> anexar.executar(chamador, direto(gasto.id(), "https://arquivos.exemplo.test/f", chave()))).mensagem())
                .contains("não é uma imagem");
    }

    @Test
    void aTelaDoComprovanteAbreSoParaODono() {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();
        String comprovante = anexar.executar(chamador, direto(gasto.id(), "https://arquivos.exemplo.test/f", chave())).valor().comprovante();
        String caminho = "/fin/n/" + comprovante.substring(2);

        Vista vista = vistas.abrir(chamador, new Vistas.Pedido(caminho, null, null, null, null));

        assertThat(vista.componentes()).extracting(c -> c.get("componente")).contains("totais", "link");
        assertThat(vista.dados().toString()).contains(comprovante, "/fin/comprovante/");
        Chamador outro = new Chamador(UUID.randomUUID(), "claude-ai", Set.of("fin:read"));
        ErroTipado erro = catchThrowableOfType(ErroTipado.class, () -> vistas.abrir(outro, new Vistas.Pedido(caminho, null, null, null, null)));
        assertThat(erro.codigo()).isEqualTo(CodigoErro.NAO_ENCONTRADO);
    }

    // Desfazer e correção

    @Test
    void desfazerDesvinculaMantemOArquivoEDesfazerODesfazerVinculaDeNovo() throws Exception {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();
        ResultadoEscrita<AnexoRegistrado> anexo = anexar.executar(chamador, direto(gasto.id(), "https://arquivos.exemplo.test/f", chave()));
        String comprovante = anexo.valor().comprovante();

        String desfeita = desfazer(anexo.operacao().referencia().id()).operacao().referencia().id();

        assertThat(resolver.executar(chamador, gasto.id()).comprovantes()).isNull();
        ComprovanteVisto solto = (ComprovanteVisto) resolver.resolver(chamador, comprovante);
        assertThat(solto.lancamento()).isNull();
        assertThat(get(caminho(solto.urlTemporaria())).statusCode()).isEqualTo(200);

        desfazer(desfeita);
        assertThat(((ComprovanteVisto) resolver.resolver(chamador, comprovante)).lancamento()).isEqualTo(gasto.id());
    }

    @Test
    void envioPelaPaginaTambemSeDesfazComFinWrite() throws Exception {
        LancamentoRegistrado gasto = gasto("foto_nota", chave()).valor();
        enviar("/fin/anexo/" + token(gasto.urlAnexo()), PNG);
        String operacao = banco.fetch("""
                select r.codigo from operacao o join referencia_curta r on r.entidade_id = o.id
                where o.dono_id = ? and o.capacidade = 'enviar_comprovante'""", dono).get(0).get(0, String.class);

        Chamador soLeitura = new Chamador(dono, "claude-ai", Set.of("fin:read"));
        assertThat(erro(() -> desfazer.executar(soLeitura, new Desfazer.Entrada(null, operacao, null, null))).codigo())
                .isEqualTo(CodigoErro.INSUFFICIENT_SCOPE);

        desfazer(operacao);
        assertThat(resolver.executar(chamador, gasto.id()).comprovantes()).isNull();
    }

    @Test
    void correcaoLevaOComprovanteEDesfazerACorrecaoODevolve() {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();
        anexar.executar(chamador, direto(gasto.id(), "https://arquivos.exemplo.test/f", chave()));

        var correcao = corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, gasto.id(), "corrigir",
                new Alteracoes("80.00", null, null, null, null, null, null, null, null), "valor errado", null));

        assertThat(resolver.executar(chamador, correcao.valor().novo()).comprovantes()).hasSize(1);
        assertThat(resolver.executar(chamador, gasto.id()).comprovantes()).isNull();

        desfazer(correcao.operacao().referencia().id());
        assertThat(resolver.executar(chamador, gasto.id()).comprovantes()).hasSize(1);
    }

    @Test
    void anexarAEstornoOuAnuladoERecusado() {
        LancamentoRegistrado gasto = gasto("conversa", chave()).valor();
        var anulacao = corrigir.executar(chamador, new CorrigirLancamento.Entrada(chave(), null, gasto.id(), "anular", null, "errado", null));

        for (String alvo : List.of(gasto.id(), anulacao.valor().estorno())) {
            assertThat(erro(() -> anexar.executar(chamador, new AnexarComprovante.Entrada(null, null, alvo, null, null))).codigo())
                    .isEqualTo(CodigoErro.VIOLACAO_INVARIANTE);
        }
    }

    private ResultadoEscrita<ReversaoRegistrada> desfazer(String alvo) {
        String token = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo, null, null)).valor().confirmacao();
        return desfazer.executar(chamador, new Desfazer.Entrada(chave(), alvo, null, token));
    }

    private AnexarComprovante.Entrada direto(String lancamento, String url, String chave) {
        return new AnexarComprovante.Entrada(chave, null, lancamento, new AnexarComprovante.ArquivoRecebido(url, "file_1"), null);
    }

    private ResultadoEscrita<LancamentoRegistrado> gasto(String origem, String chave) {
        return registrarGasto.executar(chamador, entrada(origem, chave, null));
    }

    private RegistrarGasto.Entrada entrada(String origem, String chave, Boolean simular) {
        return new RegistrarGasto.Entrada(chave, null, "100.00", "Compra", "Pix", origem, null, "Mercado", null, null, null,
                null, null, null, true, simular);
    }

    private String chaveNoBucket() {
        return banco.select(COMPROVANTE.CHAVE_ARMAZENAMENTO).from(COMPROVANTE).where(COMPROVANTE.DONO_ID.eq(dono)).fetchOne().value1();
    }

    private String chave() {
        return "chave-" + dono + "-" + chaves++;
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String caminho(String url) {
        return URI.create(url).getRawPath();
    }

    private static void semIndexacao(HttpResponse<?> resposta) {
        assertThat(resposta.headers().firstValue("X-Robots-Tag").orElseThrow()).contains("noindex");
        assertThat(resposta.headers().firstValue("Referrer-Policy").orElseThrow()).isEqualTo("no-referrer");
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

    private HttpResponse<String> enviar(String rota, byte[] arquivo) throws Exception {
        return enviar(rota, arquivo, null);
    }

    private HttpResponse<String> enviar(String rota, byte[] arquivo, String natureza) throws Exception {
        ByteArrayOutputStream corpo = new ByteArrayOutputStream();
        if (natureza != null) {
            corpo.write(("--" + LIMITE + "\r\nContent-Disposition: form-data; name=\"natureza\"\r\n\r\n" + natureza + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        corpo.write(("--" + LIMITE + "\r\nContent-Disposition: form-data; name=\"arquivo\"; filename=\"a.bin\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        corpo.write(arquivo);
        corpo.write(("\r\n--" + LIMITE + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(pedido(rota).header("Content-Type", "multipart/form-data; boundary=" + LIMITE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
}
