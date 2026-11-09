package tools.alicerce.financeiro.persistencia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BaixadorHttpTest {

    private HttpServer servidor;
    private final BaixadorHttp baixador = new BaixadorHttp();

    @BeforeEach
    void subir() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/ok", t -> {
            t.sendResponseHeaders(200, 3);
            t.getResponseBody().write(new byte[3]);
            t.close();
        });
        servidor.createContext("/redireciona", t -> {
            t.getResponseHeaders().add("Location", "/ok");
            t.sendResponseHeaders(302, -1);
            t.close();
        });
        servidor.start();
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    private URI uri(String caminho) {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + caminho);
    }

    @Test
    void baixaDentroDoLimite() {
        assertThat(baixador.baixar(uri("/ok"), 3)).hasValueSatisfying(b -> assertThat(b).hasSize(3));
    }

    @Test
    void acimaDoLimiteVoltaVazio() {
        assertThat(baixador.baixar(uri("/ok"), 2)).isEmpty();
    }

    @Test
    void naoSegueRedirecionamento() {
        assertThatThrownBy(() -> baixador.baixar(uri("/redireciona"), 3)).isInstanceOf(IllegalStateException.class);
    }
}
