// Convenções comuns a todos os módulos (dinheiro, datas, referências, erros, idempotência). Java puro.
plugins { id("alicerce.java") }

dependencies {
    testImplementation(libs.junit.jupiter.params)
}
