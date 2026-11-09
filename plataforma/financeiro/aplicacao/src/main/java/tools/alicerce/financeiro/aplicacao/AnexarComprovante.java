package tools.alicerce.financeiro.aplicacao;

import java.lang.System.Logger;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.ChaveIdempotencia;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.dominio.NaturezaImagem;
import tools.alicerce.financeiro.dominio.TipoImagem;

/**
 * Capacidade {@code anexar_comprovante}. Sem arquivo, gera um link de envio de uso único, que serve a
 * qualquer assistente e a lançamentos antigos. Com arquivo, é o recebimento direto: baixa durante a
 * chamada, porque a validade do link do cliente não é documentada, e só de hosts permitidos (SSRF).
 *
 * <p>O {@code download_url} é uma credencial temporária: não entra nos argumentos gravados nem nos logs.
 */
@Component
public class AnexarComprovante {

    public record ArquivoRecebido(String urlDownload, String idArquivo) {}

    /** @param natureza {@code comprovante} (o padrão) ou {@code foto}; só vale com arquivo, porque a página de envio pergunta */
    public record Entrada(String chaveIdempotencia, String lote, String lancamento, ArquivoRecebido arquivo, String natureza) {}

    /** O escopo da interface web para pedir um link de envio, opcional só no cliente {@code app-web}. */
    public static final String ESCOPO_INTERFACE = "fin:attach";

    private static final Logger LOG = System.getLogger(AnexarComprovante.class.getName());
    private static final String CAPACIDADE = "anexar_comprovante";

    private final RegistradorOperacoes registrador;
    private final Referencias referencias;
    private final Comprovantes comprovantes;
    private final BaixadorArquivos baixador;
    private final List<String> hostsPermitidos;

    AnexarComprovante(RegistradorOperacoes registrador, Referencias referencias, Comprovantes comprovantes,
            BaixadorArquivos baixador,
            @Value("${alicerce.financeiro.comprovantes.hosts-download}") List<String> hostsPermitidos) {
        this.registrador = registrador;
        this.referencias = referencias;
        this.comprovantes = comprovantes;
        this.baixador = baixador;
        this.hostsPermitidos = hostsPermitidos;
    }

    public ResultadoEscrita<AnexoRegistrado> executar(Chamador chamador, Entrada e) {
        if (Argumentos.vazio(e.lancamento())) {
            throw ErroTipado.camposFaltando(List.of("lancamento"), "Pergunte ao dono de qual lançamento é o comprovante.");
        }
        NaturezaImagem natureza = Comprovantes.natureza(e.natureza(), "natureza");
        return e.arquivo() == null ? gerarLink(chamador, e) : receber(chamador, e, natureza);
    }

    private ResultadoEscrita<AnexoRegistrado> gerarLink(Chamador chamador, Entrada e) {
        String token = comprovantes.sortearToken();
        PedidoEscrita pedido = new PedidoEscrita(CAPACIDADE, new Argumentos().com("lancamento", e.lancamento()).mapa(), null, e.lote());
        ResultadoEscrita<AnexoRegistrado> resultado = registrador.registrar(chamador, pedido, AnexoRegistrado.class, o -> {
            UUID id = lancamento(o.dono(), e);
            comprovantes.emitirLink(o, id, token);
            return new AnexoRegistrado(comprovantes.codigoDoLancamento(id), null, null, null, null);
        });
        return new ResultadoEscrita<>(resultado.valor().comLink(comprovantes.urlAnexo(token), comprovantes.validadeLink()),
                resultado.operacao(), false);
    }

    private ResultadoEscrita<AnexoRegistrado> receber(Chamador chamador, Entrada e, NaturezaImagem natureza) {
        ChaveIdempotencia chave = ChaveIdempotencia.ler(e.chaveIdempotencia());
        String idArquivo = e.arquivo().idArquivo();
        if (Argumentos.vazio(idArquivo) || Argumentos.vazio(e.arquivo().urlDownload())) {
            throw ErroTipado.camposFaltando(List.of("arquivo"), "O cliente não repassou o arquivo; use o link de envio.");
        }
        URI endereco = conferir(e.arquivo().urlDownload());
        lancamento(chamador.dono(), e);
        byte[] conteudo = baixar(endereco);
        Comprovantes.Arquivo arquivo = comprovantes.guardar(chamador.dono(), conteudo);
        Map<String, ?> argumentos = new Argumentos().com("lancamento", e.lancamento()).com("id_arquivo", idArquivo)
                .com("natureza", natureza.codigo()).mapa();
        return registrador.registrar(chamador, new PedidoEscrita(CAPACIDADE, argumentos, chave, e.lote()), AnexoRegistrado.class, o -> {
            UUID id = lancamento(o.dono(), e);
            return new AnexoRegistrado(comprovantes.codigoDoLancamento(id), comprovantes.indexar(o, arquivo, id, natureza).id(),
                    natureza.codigo(), null, null);
        });
    }

    private UUID lancamento(UUID dono, Entrada e) {
        UUID id = referencias.lancamento(dono, e.lancamento(), "lancamento");
        comprovantes.exigirVigente(dono, id, "lancamento");
        return id;
    }

    private byte[] baixar(URI endereco) {
        try {
            return baixador.baixar(endereco, TipoImagem.TAMANHO_MAXIMO).orElseThrow(Comprovantes::tamanhoExcedido);
        } catch (IllegalStateException falha) {
            throw ErroTipado.validacao("arquivo", "Não foi possível baixar o arquivo. Use o link de envio.");
        }
    }

    /** Só https, porta padrão, sem credenciais e num host permitido ou subdomínio dele. */
    URI conferir(String texto) {
        URI uri;
        try {
            uri = new URI(texto.strip());
        } catch (java.net.URISyntaxException invalida) {
            throw hostRecusado();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean permitido = hostsPermitidos.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || !permitido) {
            LOG.log(Logger.Level.WARNING, "Download de comprovante recusado: host {0}", host);
            throw hostRecusado();
        }
        return uri;
    }

    private static ErroTipado hostRecusado() {
        return ErroTipado.validacao("arquivo", "Esse endereço de arquivo não é aceito. Use o link de envio.");
    }
}
