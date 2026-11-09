package tools.alicerce.financeiro.adaptador.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.alicerce.convencoes.Chamador;
import tools.alicerce.convencoes.CodigoErro;
import tools.alicerce.convencoes.ErroTipado;
import tools.alicerce.financeiro.aplicacao.AnexarComprovante;
import tools.alicerce.financeiro.aplicacao.Desfazer;
import tools.alicerce.financeiro.aplicacao.Vista;
import tools.alicerce.financeiro.aplicacao.Vistas;

/**
 * A interface web: a casca PWA, pública e sem dado nenhum, e a API que devolve a vista de um endereço.
 * Quem vê dados é o dono do token, nunca o caminho da URL. As escritas ficam em {@link RotasDesfazer} e
 * {@link RotasAnexo}.
 */
@RestController
class RotasInterface {

    private static final String ESCOPO = "fin:read";

    private final Vistas vistas;
    private final String casca;
    private final String politica;
    private final Map<String, String> configuracao;

    RotasInterface(Vistas vistas, @Value("${alicerce.seguranca.emissor}") String emissor) {
        this.vistas = vistas;
        this.casca = ler("/interface/index.html");
        URI origem = URI.create(emissor);
        String autenticacao = origem.getScheme() + "://" + origem.getAuthority();
        this.politica = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self' " + autenticacao
                + "; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
        this.configuracao = Map.of("emissor", emissor, "cliente", "app-web", "escopo", "openid " + ESCOPO,
                "escopo_desfazer", "openid " + Desfazer.ESCOPO_PROPRIO, "escopo_anexo", "openid " + AnexarComprovante.ESCOPO_INTERFACE);
    }

    @GetMapping({"/", "/callback", "/fin/contas", "/fin/lancamentos", "/fin/operacoes", "/fin/relatorios", "/fin/t/{codigo}", "/fin/a/{codigo}", "/fin/f/{codigo}",
        "/fin/o/{codigo}", "/fin/s/{codigo}", "/fin/n/{codigo}", "/fin/k/{codigo}", "/fin/m/{codigo}", "/fin/compromissos", "/fin/agenda"})
    ResponseEntity<String> casca() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .header("Content-Security-Policy", politica)
                .body(casca);
    }

    @GetMapping("/web/config.json")
    ResponseEntity<Map<String, String>> configuracao() {
        // Sem cache: logo depois de uma subida, uma cópia antiga pediria ao Keycloak um escopo que ela não conhece.
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(configuracao);
    }

    @GetMapping("/api/fin/vista")
    ResponseEntity<Vista> vista(@RequestAttribute(Chamador.CHAVE_CONTEXTO) Chamador chamador, @RequestParam String caminho,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String de,
            @RequestParam(required = false) String ate, @RequestParam(required = false) String ambito,
            @RequestParam(required = false) String cliente, @RequestParam(name = "incluir_relatorios", required = false) String incluirRelatorios) {
        if (!chamador.escopos().contains(ESCOPO)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Vista vista = vistas.abrir(chamador, new Vistas.Pedido(caminho, cursor, de, ate, ambito, cliente, incluirRelatorios));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vista);
    }

    /** Dono errado e endereço inexistente respondem igual: a tela não confirma que o código existe. */
    @ExceptionHandler(ErroTipado.class)
    ResponseEntity<Map<String, String>> erro(ErroTipado erro) {
        boolean naoEncontrado = List.of(CodigoErro.NAO_ENCONTRADO, CodigoErro.REFERENCIA_INEXISTENTE).contains(erro.codigo());
        return ResponseEntity.status(naoEncontrado ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_CONTENT)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("codigo", erro.codigo().codigo(), "mensagem", erro.mensagem()));
    }

    private static String ler(String recurso) {
        try (InputStream entrada = RotasInterface.class.getResourceAsStream(recurso)) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
