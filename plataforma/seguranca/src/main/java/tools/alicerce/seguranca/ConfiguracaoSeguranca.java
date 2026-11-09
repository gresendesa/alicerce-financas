package tools.alicerce.seguranca;

import java.util.EnumSet;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Resource server OAuth: as sete checagens de token, nesta ordem, antes de qualquer capacidade. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropriedadesSeguranca.class)
public class ConfiguracaoSeguranca {

    /**
     * Checagem 1 (assinatura contra o JWKS do realm, com cache e recarga quando aparece um {@code kid}
     * desconhecido) e checagens 2 a 5. Os metadados do emissor só são lidos na primeira validação.
     */
    @Bean
    JwtDecoder decodificadorJwt(PropriedadesSeguranca propriedades) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decodificador = NimbusJwtDecoder.withIssuerLocation(propriedades.emissor()).build();
            decodificador.setJwtValidator(ValidadoresToken.criar(propriedades));
            return decodificador;
        });
    }

    @Bean
    SecurityFilterChain cadeiaSeguranca(HttpSecurity http, PropriedadesSeguranca propriedades, JwtDecoder decodificador,
            ResolvedorDono resolvedorDono, EscoposDasFerramentas escopos, ObjectProvider<RegistroRecusas> recusas) {
        PontoEntradaRecurso pontoEntrada = new PontoEntradaRecurso(propriedades);
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(pedidos -> pedidos
                .requestMatchers("/.well-known/**", "/actuator/health", "/actuator/health/**", "/error").permitAll()
                .requestMatchers(propriedades.rotasPublicas().toArray(String[]::new)).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(recurso -> recurso
                .jwt(jwt -> jwt.decoder(decodificador))
                .authenticationEntryPoint(pontoEntrada)
                .protectedResourceMetadata(metadados -> metadados.protectedResourceMetadataCustomizer(m -> m
                    .resource(propriedades.recurso())
                    .authorizationServers(servidores -> {
                        servidores.clear();
                        servidores.add(propriedades.emissor());
                    })
                    .scopes(lista -> {
                        lista.clear();
                        lista.addAll(propriedades.escopos());
                    })
                    .bearerMethods(metodos -> {
                        metodos.clear();
                        metodos.add("header");
                    })
                    .tlsClientCertificateBoundAccessTokens(false)
                    .resourceName("Alicerce · financeiro"))))
            .exceptionHandling(excecoes -> excecoes.authenticationEntryPoint(pontoEntrada))
            .addFilterAfter(new FiltroChamador(propriedades, resolvedorDono, escopos,
                    recusas.getIfAvailable(() -> (chamador, ferramenta) -> {})),
                    BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Antes da cadeia de segurança, para valer também nas respostas 401 e 403 e nos metadados. */
    @Bean
    FilterRegistrationBean<FiltroSemIndexacao> filtroSemIndexacao() {
        FilterRegistrationBean<FiltroSemIndexacao> registro = new FilterRegistrationBean<>(new FiltroSemIndexacao());
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registro.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        return registro;
    }
}
