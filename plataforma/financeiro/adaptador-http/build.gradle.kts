// Rotas públicas de comprovante; depois, a API somente leitura da interface web.
plugins { id("alicerce.java") }

dependencies {
    implementation(project(":financeiro:aplicacao"))
    implementation(libs.spring.webmvc)
}
