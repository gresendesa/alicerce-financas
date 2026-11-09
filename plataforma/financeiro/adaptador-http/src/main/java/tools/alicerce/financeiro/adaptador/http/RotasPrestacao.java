package tools.alicerce.financeiro.adaptador.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.alicerce.financeiro.aplicacao.AbrirPrestacao;

/**
 * A página pública de prestação de contas, protegida só pelo token da URL. A casca é fixa e sem dado nenhum,
 * com título genérico para a prévia dos mensageiros; os dados vêm da vista guardada no retrato do link, desenhada pelo
 * renderizador do catálogo. Sem {@code unsafe-inline}, sem recurso de fora, sem cache, sem indexação (o filtro geral) e
 * sem referenciador. Link vencido, revogado, de operação desfeita ou inexistente responde igual: 404. O token nunca vai
 * para log.
 */
@RestController
class RotasPrestacao {

    private static final String CSP_PAGINA = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final String CSP_ARQUIVO = "default-src 'none'; sandbox";
    private static final String INDISPONIVEL = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex, nofollow, noarchive, nosnippet, noimageindex">
            <meta name="color-scheme" content="light dark">
            <title>Prestação de contas</title>
            <link rel="stylesheet" href="/web/estilo.css">
            <link rel="stylesheet" href="/web/casca.css">
            </head>
            <body>
            <main id="conteudo">
            <h1>Link indisponível</h1>
            <p>Este link não abre mais: venceu, foi revogado ou não existe. Peça um novo a quem o enviou.</p>
            </main>
            </body>
            </html>
            """;

    private final AbrirPrestacao abrir;
    private final String casca;

    RotasPrestacao(AbrirPrestacao abrir) {
        this.abrir = abrir;
        this.casca = ler("/interface/publico.html");
    }

    @GetMapping("/fin/p/{token}")
    ResponseEntity<String> pagina(@PathVariable String token) {
        boolean abre = abrir.abre(token);
        return ResponseEntity.status(abre ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CSP_PAGINA).body(abre ? casca : INDISPONIVEL);
    }

    @GetMapping("/fin/p/{token}/vista")
    ResponseEntity<?> vista(@PathVariable String token) {
        return abrir.vista(token).<ResponseEntity<?>>map(v -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .header("X-Content-Type-Options", "nosniff").body(v))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(Map.of("codigo", "nao_encontrado", "mensagem", "Este link não abre mais.")));
    }

    @GetMapping("/fin/p/{token}/n/{codigo}")
    ResponseEntity<byte[]> comprovante(@PathVariable String token, @PathVariable String codigo) {
        return abrir.comprovante(token, codigo)
                .map(a -> ResponseEntity.ok().contentType(MediaType.parseMediaType(a.tipoMidia()))
                        .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", CSP_ARQUIVO)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(a.conteudo()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build());
    }

    private static String ler(String recurso) {
        try (InputStream entrada = RotasPrestacao.class.getResourceAsStream(recurso)) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
