package tools.alicerce.financeiro.app;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak de teste com o mesmo {@code realm-alicerce.json} aplicado em credencie.me, pelo mesmo
 * keycloak-config-cli. Por cima, só o que os testes precisam: senha direta no {@code claude-ai}, um
 * cliente desconhecido e dois usuários (com e sem o papel de dono).
 */
@TestConfiguration(proxyBeanMethods = false)
public class KeycloakDeTeste {

    public static final String REALM = "alicerce";
    public static final String SEGREDO_CLAUDE = "segredo-claude-ai";
    public static final String SEGREDO_DESCONHECIDO = "segredo-desconhecido";
    public static final String SENHA = "senha-de-teste";

    private static final Network REDE = Network.newNetwork();
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer(System.getProperty("alicerce.imagem.keycloak"))
            .withNetwork(REDE)
            .withNetworkAliases("keycloak");

    static {
        KEYCLOAK.start();
        Keycloak admin = KEYCLOAK.getKeycloakAdminClient();
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(REALM);
        realm.setEnabled(true);
        admin.realms().create(realm);
        aplicarRealmVersionado();
        prepararParaTestes(admin.realm(REALM));
    }

    /** Emissor dos tokens, como o Keycloak de teste o anuncia. */
    public static String emissor() {
        return KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM;
    }

    @Bean
    DynamicPropertyRegistrar propriedadesKeycloak() {
        return registro -> registro.add("alicerce.seguranca.emissor", KeycloakDeTeste::emissor);
    }

    private static void aplicarRealmVersionado() {
        try (GenericContainer<?> configCli = new GenericContainer<>(System.getProperty("alicerce.imagem.keycloak-config-cli"))) {
            configCli.withNetwork(REDE)
                    // Cópia, não bind mount: o build roda num contêiner e o daemon não enxerga o caminho dele.
                    .withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("alicerce.realm.arquivo")),
                            "/config/realm-alicerce.json")
                    .withEnv(Map.of(
                            "KEYCLOAK_URL", "http://keycloak:8080",
                            "KEYCLOAK_USER", KEYCLOAK.getAdminUsername(),
                            "KEYCLOAK_PASSWORD", KEYCLOAK.getAdminPassword(),
                            "IMPORT_FILES_LOCATIONS", "/config/realm-alicerce.json",
                            "IMPORT_CACHE_ENABLED", "false"))
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)));
            try {
                configCli.start();
            } catch (RuntimeException falha) {
                throw new IllegalStateException("keycloak-config-cli falhou:\n" + configCli.getLogs(), falha);
            }
        }
    }

    private static void prepararParaTestes(RealmResource realm) {
        // Senha direta e sem consentimento só aqui, para os testes obterem tokens sem navegador.
        ClientRepresentation claude = realm.clients().findByClientId("claude-ai").getFirst();
        claude.setDirectAccessGrantsEnabled(true);
        claude.setConsentRequired(false);
        claude.setSecret(SEGREDO_CLAUDE);
        realm.clients().get(claude.getId()).update(claude);

        ClientRepresentation desconhecido = new ClientRepresentation();
        desconhecido.setClientId("desconhecido");
        desconhecido.setPublicClient(false);
        desconhecido.setSecret(SEGREDO_DESCONHECIDO);
        desconhecido.setStandardFlowEnabled(false);
        desconhecido.setDirectAccessGrantsEnabled(true);
        realm.clients().create(desconhecido).close();
        String idDesconhecido = realm.clients().findByClientId("desconhecido").getFirst().getId();
        String finRead = realm.clientScopes().findAll().stream()
                .filter(escopo -> escopo.getName().equals("fin:read")).findFirst().orElseThrow().getId();
        realm.clients().get(idDesconhecido).addOptionalClientScope(finRead);

        // O navegador de teste abre a interface em localhost, com a porta sorteada pelo teste.
        ClientRepresentation appWeb = realm.clients().findByClientId("app-web").getFirst();
        appWeb.setRedirectUris(List.of("http://localhost:*"));
        appWeb.setWebOrigins(List.of("*"));
        appWeb.setDirectAccessGrantsEnabled(true); // para pedir tokens de fin:undo sem navegador
        realm.clients().get(appWeb.getId()).update(appWeb);

        criarUsuario(realm, "gui", true);
        criarUsuario(realm, "ana", true);
        criarUsuario(realm, "bia", true);
        criarUsuario(realm, "cia", true);
        criarUsuario(realm, "intruso", false);
    }

    private static void criarUsuario(RealmResource realm, String nome, boolean dono) {
        UserRepresentation usuario = new UserRepresentation();
        usuario.setUsername(nome);
        usuario.setEmail(nome + "@exemplo.test");
        usuario.setFirstName(nome);
        usuario.setLastName("Teste");
        usuario.setEmailVerified(true);
        usuario.setEnabled(true);
        CredentialRepresentation senha = new CredentialRepresentation();
        senha.setType(CredentialRepresentation.PASSWORD);
        senha.setValue(SENHA);
        senha.setTemporary(false);
        usuario.setCredentials(List.of(senha));
        realm.users().create(usuario).close();

        String id = realm.users().searchByUsername(nome, true).getFirst().getId();
        UserRepresentation criado = realm.users().get(id).toRepresentation();
        criado.setRequiredActions(List.of()); // o realm exige TOTP de novos usuários; aqui não.
        realm.users().get(id).update(criado);
        if (dono) {
            realm.users().get(id).roles().realmLevel().add(List.of(realm.roles().get("fin-owner").toRepresentation()));
        }
    }
}
