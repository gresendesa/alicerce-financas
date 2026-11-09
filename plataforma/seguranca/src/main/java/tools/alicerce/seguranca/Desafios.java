package tools.alicerce.seguranca;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Respostas 401 e 403 com o cabeçalho {@code WWW-Authenticate} do RFC 6750 e do RFC 9728. */
final class Desafios {

    private Desafios() {}

    static void naoAutenticado(HttpServletResponse resposta, PropriedadesSeguranca propriedades, String erro,
            String descricao) throws IOException {
        StringBuilder desafio = new StringBuilder("Bearer ");
        if (erro != null) {
            desafio.append("error=\"").append(erro).append("\", ");
            if (descricao != null) {
                desafio.append("error_description=\"").append(semAspas(descricao)).append("\", ");
            }
        }
        desafio.append("resource_metadata=\"").append(propriedades.urlMetadados()).append("\", ")
                .append("scope=\"").append(propriedades.escopoInicial()).append('"');
        escrever(resposta, HttpServletResponse.SC_UNAUTHORIZED, desafio.toString(),
                erro != null ? erro : "unauthorized", descricao, null);
    }

    static void escopoInsuficiente(HttpServletResponse resposta, PropriedadesSeguranca propriedades, String escopo)
            throws IOException {
        String desafio = "Bearer error=\"insufficient_scope\", scope=\"" + escopo + "\", resource_metadata=\""
                + propriedades.urlMetadados() + '"';
        escrever(resposta, HttpServletResponse.SC_FORBIDDEN, desafio, "insufficient_scope",
                "O token não tem o escopo " + escopo, escopo);
    }

    static void acessoNegado(HttpServletResponse resposta, String descricao) throws IOException {
        escrever(resposta, HttpServletResponse.SC_FORBIDDEN, null, "access_denied", descricao, null);
    }

    private static void escrever(HttpServletResponse resposta, int status, String desafio, String erro,
            String descricao, String escopo) throws IOException {
        resposta.setStatus(status);
        if (desafio != null) {
            resposta.setHeader(HttpHeaders.WWW_AUTHENTICATE, desafio);
        }
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding("UTF-8");
        StringBuilder corpo = new StringBuilder("{\"error\":\"").append(erro).append('"');
        if (descricao != null) {
            corpo.append(",\"error_description\":\"").append(semAspas(descricao)).append('"');
        }
        if (escopo != null) {
            corpo.append(",\"scope\":\"").append(escopo).append('"');
        }
        resposta.getWriter().write(corpo.append('}').toString());
    }

    private static String semAspas(String texto) {
        return texto.replace("\\", "").replace("\"", "'");
    }
}
