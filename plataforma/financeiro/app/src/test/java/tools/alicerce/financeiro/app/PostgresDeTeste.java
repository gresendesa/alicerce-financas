package tools.alicerce.financeiro.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Postgres de teste com os mesmos dois usuários da produção: o Flyway entra como o dono do esquema
 * (o usuário do contêiner) e a aplicação como {@code financeiro_app}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresDeTeste {

    public static final String USUARIO_APLICACAO = "financeiro_app";
    static final String SENHA_APLICACAO = "senha-de-teste-da-aplicacao";

    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16.15-alpine");
    }

    @Bean
    DynamicPropertyRegistrar usuariosDoBanco(PostgreSQLContainer postgres) {
        return registro -> {
            registro.add("spring.datasource.url", postgres::getJdbcUrl);
            registro.add("spring.datasource.username", () -> USUARIO_APLICACAO);
            registro.add("spring.datasource.password", () -> SENHA_APLICACAO);
            registro.add("spring.flyway.user", postgres::getUsername);
            registro.add("spring.flyway.password", postgres::getPassword);
            // A marca de conta de teste do obter_contexto: na produção, CONTAS_TESTE em infra/.env.
            registro.add("alicerce.financeiro.contas-teste", () -> "ana, aceite");
        };
    }
}
