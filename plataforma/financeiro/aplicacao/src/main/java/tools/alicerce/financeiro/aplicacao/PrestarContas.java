package tools.alicerce.financeiro.aplicacao;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioLinks.Link;
import tools.alicerce.financeiro.dominio.Ambito;
import tools.alicerce.financeiro.dominio.Dono;
import tools.alicerce.financeiro.dominio.Periodo;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * Capacidade {@code prestar_contas}: publica os gastos e entradas de um âmbito num link que abre sem login,
 * ou revoga um link. Criar grava um retrato com a prestação e a descrição da tela, e o link aponta para ele.
 *
 * <p>O token sai só na resposta da criação. Nem ele nem a URL entram nos argumentos gravados, no resultado ou nos logs:
 * o banco guarda só o hash, e a revogação grava o id do retrato. Sem chave de idempotência, como o link de envio:
 * repetir o pedido cria outro link.
 */
@Component
public class PrestarContas {

    /**
     * @param revogar a URL do link ou o id curto do retrato dele; sozinho, sem os outros campos
     * @param validadeDias por quantos dias o link abre; padrão 90, no máximo 365
     */
    public record Entrada(String lote, String ambito, String de, String ate, String periodo, Boolean incluirComprovantes,
            Integer validadeDias, String revogar) {}

    public static final String CAPACIDADE = "prestar_contas";
    static final int VALIDADE_PADRAO = 90;
    static final int VALIDADE_MAXIMA = 365;
    static final String CAMINHO = "/fin/p/";

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern NA_URL = Pattern.compile(".*" + Pattern.quote(CAMINHO) + "([A-Za-z0-9_-]{43})(?:[/?#].*)?");

    private final RegistradorOperacoes registrador;
    private final RepositorioDono donos;
    private final RepositorioRetratos retratos;
    private final RepositorioLinks links;
    private final RepositorioEstrutura estrutura;
    private final Referencias referencias;
    private final CalculoDePrestacao calculo;
    private final Enderecos enderecos;

    PrestarContas(RegistradorOperacoes registrador, RepositorioDono donos, RepositorioRetratos retratos, RepositorioLinks links,
            RepositorioEstrutura estrutura, Referencias referencias, CalculoDePrestacao calculo, Enderecos enderecos) {
        this.registrador = registrador;
        this.donos = donos;
        this.retratos = retratos;
        this.links = links;
        this.estrutura = estrutura;
        this.referencias = referencias;
        this.calculo = calculo;
        this.enderecos = enderecos;
    }

    public ResultadoEscrita<PrestacaoRegistrada> executar(Chamador chamador, Entrada e) {
        return Argumentos.vazio(e.revogar()) ? criar(chamador, e) : revogar(chamador, e);
    }

    private ResultadoEscrita<PrestacaoRegistrada> criar(Chamador chamador, Entrada e) {
        boolean incluir = Boolean.TRUE.equals(e.incluirComprovantes());
        Map<String, ?> argumentos = new Argumentos().com("ambito", e.ambito()).com("de", e.de()).com("ate", e.ate())
                .com("periodo", e.periodo()).com("incluir_comprovantes", e.incluirComprovantes()).com("validade_dias", e.validadeDias()).mapa();
        String token = sortearToken();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, argumentos, null, e.lote());
        ResultadoEscrita<PrestacaoRegistrada> resultado = registrador.registrar(chamador, pedido, PrestacaoRegistrada.class,
                o -> gravar(o, chamador, e, incluir, token));
        return new ResultadoEscrita<>(resultado.valor().comUrl(enderecos.urlPrestacao(token)), resultado.operacao(), false);
    }

    private PrestacaoRegistrada gravar(OperacaoEmCurso operacao, Chamador chamador, Entrada e, boolean incluir, String token) {
        if (Argumentos.vazio(e.ambito())) {
            throw ErroTipado.camposFaltando(List.of("ambito"), "Pergunte ao dono de qual âmbito é a prestação de contas, como família.");
        }
        int validade = e.validadeDias() == null ? VALIDADE_PADRAO : e.validadeDias();
        if (validade < 1 || validade > VALIDADE_MAXIMA) {
            throw ErroTipado.validacao("validade_dias", "A validade vai de 1 a " + VALIDADE_MAXIMA + " dias; o padrão é " + VALIDADE_PADRAO + ".");
        }
        UUID dono = operacao.dono();
        Dono dados = donos.buscar(dono).orElseThrow();
        Ambito ambito = referencias.ambitoParaConsultar(dono, e.ambito(), "ambito");
        Periodo periodo = ResumirPeriodo.periodo(e.de(), e.ate(), e.periodo());
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        OffsetDateTime expira = agora.plusDays(validade);
        LocalDate validaAte = expira.atZoneSameInstant(dados.fuso()).toLocalDate();
        ParametrosPrestacao parametros = new ParametrosPrestacao(periodo.de().toString(), periodo.ate().toString(), ambito.id().toString(),
                ambito.nome(), incluir, validade);
        PrestacaoVista prestacao = calculo.calcular(dono, dados.moeda(), parametros, validaAte, agora);

        UUID id = UUID.randomUUID();
        retratos.inserir(operacao, new RepositorioRetratos.Retrato(id, CAPACIDADE, TelaPrestacao.json(parametros), agora,
                TelaPrestacao.json(TelaPrestacao.montar(prestacao)), chamador.cliente()));
        operacao.retratoGravado(id);
        ReferenciaCurta referencia = operacao.referenciar(TipoReferencia.RETRATO, id);
        UUID link = UUID.randomUUID();
        links.inserir(operacao, link, hash(token), id, incluir, expira);
        operacao.linkCriado(link);

        int comprovantes = prestacao.comprovantes() == null ? 0 : prestacao.comprovantes().size();
        int notas = prestacao.notas() == null ? 0 : prestacao.notas().size();
        return new PrestacaoRegistrada(referencia.id(), enderecos.url(referencia), null, false, ambito.nome(), prestacao.de(), prestacao.ate(),
                prestacao.moeda(), prestacao.gastos(), prestacao.entradas(), prestacao.quantidadeLancamentos(), comprovantes, notas, prestacao.validaAte(),
                oQueMostra(incluir), "Antes de o dono enviar a URL, diga que ela abre sem login, até valida_ate, e o que mostra (o_que_mostra). "
                        + "Para revogar, chame prestar_contas com revogar e a URL ou o id " + referencia.id() + ".");
    }

    private static String oQueMostra(boolean incluir) {
        return "A página abre sem login para quem tiver a URL e mostra só este âmbito e período: os totais de gastos e entradas, a quebra "
                + "por categoria e cada lançamento (data, descrição, estabelecimento, tipo do meio de pagamento e valor)"
                + (incluir ? ", as imagens de comprovante (fotos ficam fora) e as chaves das notas fiscais eletrônicas" : "; sem comprovantes")
                + ". Nunca mostra saldos, contas, nomes de meios de pagamento nem outros âmbitos. É um retrato: corrigir um lançamento "
                + "depois não muda a página; para isso, gere um link novo e revogue este.";
    }

    private ResultadoEscrita<PrestacaoRegistrada> revogar(Chamador chamador, Entrada e) {
        if (!Argumentos.vazio(e.ambito()) || !Argumentos.vazio(e.de()) || !Argumentos.vazio(e.ate()) || !Argumentos.vazio(e.periodo())
                || e.incluirComprovantes() != null || e.validadeDias() != null) {
            throw ErroTipado.validacao("revogar", "Para revogar, envie só revogar, com a URL do link ou o id do retrato dele.");
        }
        UUID dono = chamador.dono();
        Link link = localizar(dono, e.revogar().strip()).orElseThrow(() -> ErroTipado.referencia(CodigoErro.REFERENCIA_INEXISTENTE, "revogar",
                "Não há link de prestação de contas com esse endereço na conta do dono.", List.of(),
                "Confira a URL com o dono, ou use o id s- do retrato que prestar_contas devolveu."));
        String retrato = estrutura.referenciaDe(dono, TipoReferencia.RETRATO, link.retrato()).orElseThrow();
        // Grava o id do retrato, nunca a URL: ela leva o token.
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, new Argumentos().com("revogar", retrato).mapa(), null, e.lote());
        return registrador.registrar(chamador, pedido, PrestacaoRegistrada.class, o -> {
            Link atual = links.doRetrato(o.dono(), link.retrato()).orElseThrow();
            if (!atual.criacaoAplicada()) {
                throw ErroTipado.validacao("revogar", "Este link já não abre: a operação que o criou foi desfeita.");
            }
            if (atual.revogado()) {
                throw ErroTipado.validacao("revogar", "Este link já está revogado.");
            }
            links.revogar(o, atual.id(), OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC)));
            o.linkRevogado(atual.id());
            ReferenciaCurta referencia = ReferenciaCurta.ler(retrato, TipoReferencia.MODULO).orElseThrow();
            return new PrestacaoRegistrada(retrato, enderecos.url(referencia), null, true, null, null, null, null, null, null, null, null, null,
                    null, "O link não abre mais: quem tentar vê a mesma página de link inexistente. O retrato continua guardado.",
                    "Desfazer esta operação faz o link voltar a abrir até o fim da validade.");
        });
    }

    /** Pela URL (ou só o token), ou pelo id do retrato. Link de outro dono não existe. */
    private Optional<Link> localizar(UUID dono, String texto) {
        Matcher url = NA_URL.matcher(texto);
        String token = url.matches() ? url.group(1) : TOKEN.matcher(texto).matches() ? texto : null;
        if (token != null) {
            return links.porHash(hash(token)).filter(l -> l.dono().equals(dono));
        }
        if (texto.contains(CAMINHO)) {
            return Optional.empty();
        }
        UUID retrato;
        try {
            retrato = referencias.retrato(dono, texto, "revogar");
        } catch (ErroTipado naoEncontrado) {
            if (naoEncontrado.codigo() == CodigoErro.REFERENCIA_INEXISTENTE) {
                return Optional.empty();
            }
            throw naoEncontrado;
        }
        return links.doRetrato(dono, retrato);
    }

    static String sortearToken() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossivel) {
            throw new IllegalStateException(impossivel);
        }
    }
}
