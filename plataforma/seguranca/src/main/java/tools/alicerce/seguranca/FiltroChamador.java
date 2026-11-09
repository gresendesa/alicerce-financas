package tools.alicerce.seguranca;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.alicerce.convencoes.Chamador;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checagens 6 e 7 da seção Autorização, depois da validação do token: o papel de dono, o escopo
 * da tool chamada e a resolução do dono. Ao fim, o {@link Chamador} fica num atributo da requisição.
 */
final class FiltroChamador extends OncePerRequestFilter {

    /** Atributo da requisição que guarda o {@link Chamador}. */
    static final String ATRIBUTO = Chamador.CHAVE_CONTEXTO;

    private final PropriedadesSeguranca propriedades;
    private final ResolvedorDono resolvedorDono;
    private final EscoposDasFerramentas escopos;
    private final RegistroRecusas recusas;
    private final JsonMapper json = JsonMapper.builder().build();

    private static final System.Logger LOG = System.getLogger(FiltroChamador.class.getName());

    FiltroChamador(PropriedadesSeguranca propriedades, ResolvedorDono resolvedorDono, EscoposDasFerramentas escopos,
            RegistroRecusas recusas) {
        this.recusas = recusas;
        this.propriedades = propriedades;
        this.resolvedorDono = resolvedorDono;
        this.escopos = escopos;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken autenticacao)) {
            cadeia.doFilter(requisicao, resposta);
            return;
        }
        Jwt token = autenticacao.getToken();

        // 6. Dono: o papel é exigido em toda requisição; tirá-lo no Keycloak corta o acesso.
        if (!papeisDeRealm(token).contains(propriedades.papelDono())) {
            Desafios.acessoNegado(resposta, "O usuário não tem o papel " + propriedades.papelDono());
            return;
        }

        // 7. Escopo exigido pela tool chamada.
        RequisicaoComCorpo comCorpo = new RequisicaoComCorpo(requisicao);
        Set<String> concedidos = escoposConcedidos(token);
        Optional<String> ferramenta = ferramentaChamada(comCorpo.corpo());
        Optional<String> exigido = ferramenta.flatMap(escopos::escopoDe);
        if (exigido.isPresent() && !concedidos.contains(exigido.get())) {
            gravarRecusa(token, concedidos, ferramenta.get());
            Desafios.escopoInsuficiente(resposta, propriedades, exigido.get());
            return;
        }

        comCorpo.setAttribute(ATRIBUTO, chamador(token, concedidos));
        cadeia.doFilter(comCorpo, resposta);
    }

    private Chamador chamador(Jwt token, Set<String> concedidos) {
        UUID dono = resolvedorDono.resolver(token.getClaimAsString("iss"), token.getSubject());
        return new Chamador(dono, token.getClaimAsString("azp"), concedidos, token.getClaimAsString("preferred_username"));
    }

    /** Uma falha ao gravar não muda a resposta: o 403 sai do mesmo jeito. */
    private void gravarRecusa(Jwt token, Set<String> concedidos, String ferramenta) {
        try {
            recusas.escopoInsuficiente(chamador(token, concedidos), ferramenta);
        } catch (RuntimeException falha) {
            LOG.log(System.Logger.Level.WARNING, "A recusa por escopo de " + ferramenta + " não foi gravada", falha);
        }
    }

    /** O nome da tool, quando a mensagem JSON-RPC é um {@code tools/call}. */
    private Optional<String> ferramentaChamada(byte[] corpo) {
        if (corpo.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode mensagem = json.readTree(corpo);
            if (mensagem.isObject() && "tools/call".equals(mensagem.path("method").asString(null))) {
                return Optional.ofNullable(mensagem.path("params").path("name").asString(null));
            }
        } catch (RuntimeException corpoInvalido) {
            // JSON inválido: o servidor MCP responde com o erro de protocolo adequado.
        }
        return Optional.empty();
    }

    private static Set<String> escoposConcedidos(Jwt token) {
        String escopo = token.getClaimAsString("scope");
        return escopo == null || escopo.isBlank() ? Set.of() : Set.of(escopo.trim().split("\\s+"));
    }

    private static Set<String> papeisDeRealm(Jwt token) {
        Map<String, Object> acessoRealm = token.getClaimAsMap("realm_access");
        if (acessoRealm != null && acessoRealm.get("roles") instanceof Collection<?> papeis) {
            Set<String> nomes = new HashSet<>();
            papeis.forEach(papel -> nomes.add(String.valueOf(papel)));
            return nomes;
        }
        return Set.of();
    }
}
