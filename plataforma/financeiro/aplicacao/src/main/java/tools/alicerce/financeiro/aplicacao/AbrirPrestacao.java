package tools.alicerce.financeiro.aplicacao;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.PrestacaoVista.ComprovantePrestado;
import tools.alicerce.financeiro.aplicacao.RepositorioLinks.Link;
import tools.alicerce.financeiro.dominio.NaturezaImagem;
import tools.alicerce.financeiro.dominio.TipoReferencia;

/**
 * A leitura da página pública de prestação de contas. Resolve o token, carrega o retrato do link e nada mais:
 * nenhuma consulta ao livro-razão sai daqui. Token vencido, revogado, de operação desfeita ou que não existe dá o
 * mesmo vazio, e nada é registrado com ele.
 */
@Component
public class AbrirPrestacao {

    private static final Clock RELOGIO = Clock.systemUTC();
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODIGO = Pattern.compile("[A-Z0-9]{6}");

    private final RepositorioLinks links;
    private final RepositorioRetratos retratos;
    private final RepositorioEstrutura estrutura;
    private final RepositorioComprovantes comprovantes;
    private final ArmazenamentoComprovantes armazenamento;
    private final Enderecos enderecos;

    AbrirPrestacao(RepositorioLinks links, RepositorioRetratos retratos, RepositorioEstrutura estrutura, RepositorioComprovantes comprovantes,
            ArmazenamentoComprovantes armazenamento, Enderecos enderecos) {
        this.links = links;
        this.retratos = retratos;
        this.estrutura = estrutura;
        this.comprovantes = comprovantes;
        this.armazenamento = armazenamento;
        this.enderecos = enderecos;
    }

    /** O link abre agora? A casca da página só sai para link que abre. */
    public boolean abre(String token) {
        return aberta(token).isPresent();
    }

    /** A tela guardada no retrato, com o endereço de cada comprovante pelo próprio link. */
    public Optional<Vista> vista(String token) {
        return aberta(token).map(a -> {
            PrestacaoVista prestacao = a.guardada().prestacao();
            if (prestacao.comprovantes() != null) {
                List<ComprovantePrestado> comUrl = prestacao.comprovantes().stream()
                        .map(c -> c.comUrl(enderecos.urlComprovantePrestacao(token, semPrefixo(c.codigo())))).toList();
                prestacao = prestacao.comComprovantes(comUrl);
            }
            return TelaPrestacao.abrir(a.guardada(), prestacao);
        });
    }

    /** Só um comprovante que o retrato do link lista, e só se o link foi criado com comprovantes. */
    public Optional<AbrirComprovante.ArquivoAberto> comprovante(String token, String codigo) {
        if (codigo == null || !CODIGO.matcher(codigo.toUpperCase(Locale.ROOT)).matches()) {
            return Optional.empty();
        }
        String id = TipoReferencia.COMPROVANTE.prefixo() + "-" + codigo.toUpperCase(Locale.ROOT);
        return aberta(token)
                .filter(a -> a.link().incluirComprovantes() && a.guardada().prestacao().comprovantes() != null
                        && a.guardada().prestacao().comprovantes().stream().anyMatch(c -> c.codigo().equals(id)))
                .flatMap(a -> estrutura.entidadeDaReferencia(a.link().dono(), id).flatMap(c -> comprovantes.buscar(a.link().dono(), c)))
                .filter(i -> NaturezaImagem.COMPROVANTE.codigo().equals(i.natureza()))
                .map(i -> new AbrirComprovante.ArquivoAberto(i.tipoMidia(), armazenamento.ler(i.chave())));
    }

    private record Aberta(Link link, TelaPrestacao.Guardada guardada) {}

    private Optional<Aberta> aberta(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        OffsetDateTime agora = OffsetDateTime.now(RELOGIO.withZone(ZoneOffset.UTC));
        return links.porHash(PrestarContas.hash(token)).filter(l -> l.abre(agora))
                .flatMap(l -> retratos.buscar(l.dono(), l.retrato())
                        .filter(r -> r.capacidade().equals(PrestarContas.CAPACIDADE))
                        .map(r -> new Aberta(l, TelaPrestacao.ler(r.resultado()))));
    }

    private static String semPrefixo(String codigo) {
        return codigo.substring(codigo.indexOf('-') + 1);
    }
}
