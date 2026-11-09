// Validação de token, escopos e resolução do dono. Comum a todos os módulos.
plugins { id("alicerce.java") }

dependencies {
    api(project(":convencoes"))
    api(libs.spring.security.config)
    api(libs.spring.security.web)
    api(libs.spring.security.oauth2.resource.server)
    api(libs.spring.security.oauth2.jose)
    implementation(libs.spring.boot)
    implementation(libs.spring.webmvc)
    implementation(libs.jackson.databind)
    compileOnly(libs.jakarta.servlet.api)
}
