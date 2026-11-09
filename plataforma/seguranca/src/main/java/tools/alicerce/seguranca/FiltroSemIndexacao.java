package tools.alicerce.seguranca;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Segunda camada da regra "nada é indexável": o proxy acrescenta os mesmos cabeçalhos a tudo o que
 * sai; a aplicação também os emite, para a regra valer mesmo sem o proxy.
 */
public final class FiltroSemIndexacao extends OncePerRequestFilter {

    public static final String X_ROBOTS_TAG = "noindex, nofollow, noarchive, nosnippet, noimageindex";

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        resposta.setHeader("X-Robots-Tag", X_ROBOTS_TAG);
        resposta.setHeader("Referrer-Policy", "no-referrer");
        cadeia.doFilter(requisicao, resposta);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
