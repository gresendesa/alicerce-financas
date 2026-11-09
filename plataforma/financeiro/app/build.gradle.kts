// Aplicação Spring Boot: junta os módulos e roda os testes de arquitetura e de integração.
plugins {
    id("alicerce.java")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":seguranca"))
    implementation(project(":financeiro:aplicacao"))
    implementation(project(":financeiro:adaptador-mcp"))
    implementation(project(":financeiro:adaptador-http"))
    implementation(project(":financeiro:persistencia"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.ai.starter.mcp.server.webmvc)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.boot.starter.jooq)
    testImplementation(libs.flyway.core)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.keycloak)
    testImplementation(libs.testcontainers.minio)
    testImplementation(platform(libs.awssdk.bom))
    testImplementation(libs.awssdk.s3)
    testImplementation(libs.awssdk.url.connection)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.selenium.java)
    testImplementation(libs.axe.selenium)
}

tasks.bootJar { archiveFileName = "financeiro.jar" }
tasks.jar { enabled = false }

tasks.test {
    // O teste de autorização aplica o mesmo realm-alicerce.json usado em credencie.me.
    systemProperty("alicerce.realm.arquivo", rootProject.file("infra/keycloak/realm-alicerce.json").absolutePath)
    systemProperty("alicerce.imagem.keycloak", "quay.io/keycloak/keycloak:${libs.versions.keycloak.imagem.get()}")
    systemProperty("alicerce.imagem.keycloak-config-cli", "adorsys/keycloak-config-cli:${libs.versions.keycloak.config.cli.imagem.get()}")
    systemProperty("alicerce.imagem.minio", libs.versions.minio.imagem.get())
    systemProperty("alicerce.imagem.chromium", libs.versions.chromium.imagem.get())
    systemProperty("alicerce.imagem.caddy", libs.versions.caddy.imagem.get())
    systemProperty("alicerce.caddyfile", rootProject.file("infra/caddy/Caddyfile").absolutePath)
    inputs.file(rootProject.file("infra/keycloak/realm-alicerce.json"))
    inputs.file(rootProject.file("infra/caddy/Caddyfile"))
}
