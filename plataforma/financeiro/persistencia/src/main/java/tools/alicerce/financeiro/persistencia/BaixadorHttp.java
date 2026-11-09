package tools.alicerce.financeiro.persistencia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.alicerce.financeiro.aplicacao.BaixadorArquivos;

/** Download com tempo e tamanho limitados. Redirecionamentos não são seguidos: sairiam da lista de hosts. */
@Component
class BaixadorHttp implements BaixadorArquivos {

    private static final Duration TEMPO = Duration.ofSeconds(20);

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public Optional<byte[]> baixar(URI endereco, int limite) {
        HttpRequest pedido = HttpRequest.newBuilder(endereco).timeout(TEMPO).GET().build();
        try {
            HttpResponse<InputStream> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream corpo = resposta.body()) {
                if (resposta.statusCode() != 200) {
                    throw new IllegalStateException("Download respondeu " + resposta.statusCode());
                }
                ByteArrayOutputStream saida = new ByteArrayOutputStream();
                byte[] pedaco = new byte[64 * 1024];
                int lidos;
                while ((lidos = corpo.read(pedaco)) != -1) {
                    saida.write(pedaco, 0, lidos);
                    if (saida.size() > limite) {
                        return Optional.empty();
                    }
                }
                return Optional.of(saida.toByteArray());
            }
        } catch (IOException falha) {
            throw new IllegalStateException("Download falhou", falha);
        } catch (InterruptedException interrompido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download interrompido", interrompido);
        }
    }
}
