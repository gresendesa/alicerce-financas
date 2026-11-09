package tools.alicerce.financeiro.adaptador.http;

import java.io.IOException;
import java.lang.System.Logger;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.AbrirComprovante;
import tools.alicerce.financeiro.aplicacao.AnexoRegistrado;
import tools.alicerce.financeiro.aplicacao.EnviarComprovante;

/**
 * As rotas públicas de comprovante, protegidas pelo token da URL. Os cabeçalhos valem para a imagem
 * também: mesmo um arquivo malicioso que passe pela assinatura não executa no domínio.
 */
@RestController
class RotasComprovante {

    private static final Logger LOG = System.getLogger(RotasComprovante.class.getName());
    private static final String CSP_PAGINA = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    private static final String CSP_ARQUIVO = "default-src 'none'; sandbox";

    private final EnviarComprovante enviar;
    private final AbrirComprovante abrir;

    RotasComprovante(EnviarComprovante enviar, AbrirComprovante abrir) {
        this.enviar = enviar;
        this.abrir = abrir;
    }

    @GetMapping("/fin/anexo/{token}")
    ResponseEntity<String> pagina(@PathVariable String token) {
        return enviar.valido(token) ? pagina(HttpStatus.OK, PaginaEnvio.formulario())
                : pagina(HttpStatus.GONE, PaginaEnvio.aviso("Link inválido", "Este link já foi usado ou venceu. Peça outro ao assistente ou pela tela do lançamento."));
    }

    @PostMapping("/fin/anexo/{token}")
    ResponseEntity<String> enviar(@PathVariable String token, @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam(name = "natureza", required = false) String natureza) throws IOException {
        try {
            AnexoRegistrado anexo = enviar.enviar(token, arquivo.getBytes(), natureza).valor();
            boolean foto = "foto".equals(anexo.natureza());
            return pagina(HttpStatus.CREATED, PaginaEnvio.recebido(foto ? "Foto recebida" : "Comprovante recebido",
                    (foto ? "Guardada" : "Guardado") + " como " + anexo.comprovante() + ".", enviar.urlDoLancamento(anexo.lancamento())));
        } catch (ErroTipado erro) {
            if (erro.codigo() == CodigoErro.VALIDACAO_FALHOU) {
                return pagina(HttpStatus.UNPROCESSABLE_CONTENT, PaginaEnvio.aviso("Arquivo recusado", erro.mensagem()));
            }
            return pagina(HttpStatus.GONE, PaginaEnvio.aviso("Link inválido", erro.mensagem()));
        }
    }

    @GetMapping("/fin/comprovante/{token}")
    ResponseEntity<byte[]> arquivo(@PathVariable String token) {
        return abrir.abrir(token)
                .map(a -> ResponseEntity.ok().contentType(MediaType.parseMediaType(a.tipoMidia()))
                        .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", CSP_ARQUIVO)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(a.conteudo()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).cacheControl(CacheControl.noStore()).build());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<String> grandeDemais(MaxUploadSizeExceededException erro) {
        return pagina(HttpStatus.CONTENT_TOO_LARGE, PaginaEnvio.aviso("Arquivo recusado", "O arquivo passa de 10 MB."));
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<String> formularioInvalido(MultipartException erro) {
        LOG.log(Logger.Level.INFO, "Envio de comprovante mal formado: {0}", erro.getClass().getSimpleName());
        return pagina(HttpStatus.BAD_REQUEST, PaginaEnvio.aviso("Arquivo recusado", "Escolha uma imagem e envie de novo."));
    }

    private static ResponseEntity<String> pagina(HttpStatus status, String html) {
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CSP_PAGINA).body(html);
    }
}
