package tools.alicerce.seguranca;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração do recurso protegido.
 *
 * @param emissor emissor exato dos tokens ({@code iss}), ex. {@code https://credencie.me/realms/alicerce}
 * @param recurso identificador do recurso e audiência exigida, ex. {@code https://fin.alicerce.tools/mcp}
 * @param clientes clientes conhecidos ({@code azp}); qualquer outro é recusado
 * @param papelDono papel de realm exigido em toda requisição
 * @param escopos escopos do módulo, anunciados nos metadados do recurso
 * @param escopoInicial escopo pedido no desafio 401
 * @param origensPermitidas valores aceitos no cabeçalho {@code Origin}
 * @param rotasPublicas rotas sem token OAuth, protegidas por um token próprio na URL
 */
@ConfigurationProperties("alicerce.seguranca")
public record PropriedadesSeguranca(
        String emissor,
        String recurso,
        List<String> clientes,
        String papelDono,
        List<String> escopos,
        String escopoInicial,
        List<String> origensPermitidas,
        List<String> rotasPublicas) {

    /** URL dos metadados do recurso (RFC 9728): {@code /.well-known/oauth-protected-resource} + caminho. */
    public String urlMetadados() {
        URI uri = URI.create(recurso);
        return uri.getScheme() + "://" + uri.getRawAuthority() + "/.well-known/oauth-protected-resource" + uri.getRawPath();
    }
}
