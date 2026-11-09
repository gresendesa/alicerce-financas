// Um caso de uso por capacidade.
plugins { id("alicerce.java") }

dependencies {
    api(project(":financeiro:dominio"))
    implementation(project(":seguranca"))
    implementation(libs.spring.context)
    implementation(libs.spring.tx)
    implementation(libs.jackson.databind)
}
