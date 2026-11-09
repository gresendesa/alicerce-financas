package tools.alicerce.seguranca;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 401 com {@code resource_metadata} e o escopo inicial: o assistente descobre o servidor de autorização por aqui. */
final class PontoEntradaRecurso implements AuthenticationEntryPoint {

    private final PropriedadesSeguranca propriedades;

    PontoEntradaRecurso(PropriedadesSeguranca propriedades) {
        this.propriedades = propriedades;
    }

    @Override
    public void commence(HttpServletRequest requisicao, HttpServletResponse resposta, AuthenticationException excecao)
            throws IOException {
        if (excecao instanceof OAuth2AuthenticationException oauth) {
            Desafios.naoAutenticado(resposta, propriedades, oauth.getError().getErrorCode(),
                    oauth.getError().getDescription());
        } else {
            Desafios.naoAutenticado(resposta, propriedades, null, null);
        }
    }
}
