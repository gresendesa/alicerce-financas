package tools.alicerce.seguranca;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/** Guarda o corpo da requisição para ele ser lido pelo filtro e de novo pelo servidor MCP. */
final class RequisicaoComCorpo extends HttpServletRequestWrapper {

    private final byte[] corpo;

    RequisicaoComCorpo(HttpServletRequest requisicao) throws IOException {
        super(requisicao);
        this.corpo = requisicao.getInputStream().readAllBytes();
    }

    byte[] corpo() {
        return corpo;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream origem = new ByteArrayInputStream(corpo);
        return new ServletInputStream() {
            @Override
            public int read() {
                return origem.read();
            }

            @Override
            public int read(byte[] destino, int inicio, int tamanho) {
                return origem.read(destino, inicio, tamanho);
            }

            @Override
            public boolean isFinished() {
                return origem.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener ouvinte) {
                throw new UnsupportedOperationException("leitura assíncrona não suportada");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String codificacao = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(getInputStream(),
                codificacao != null ? java.nio.charset.Charset.forName(codificacao) : StandardCharsets.UTF_8));
    }
}
