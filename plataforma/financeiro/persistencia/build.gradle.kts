// jOOQ e migrações Flyway. As classes jOOQ ficam versionadas em src/generated/java:
// `gerarJooq` as regenera a partir das migrações; `conferirJooq` (parte do check) falha se estiverem desatualizadas.
plugins { id("alicerce.java") }

dependencies {
    implementation(project(":financeiro:aplicacao"))
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(platform(libs.awssdk.bom))
    // O cliente HTTP é o do JDK: sem Netty nem Apache na imagem.
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation(libs.awssdk.url.connection)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
}

val dirMigracoes = layout.projectDirectory.dir("src/main/resources/db/migration")
val versionado = layout.projectDirectory.dir("src/generated/java")
val pacoteJooq = "tools.alicerce.financeiro.persistencia.jooq"
val imagemPostgresJooq = "postgres:16.15-alpine"

sourceSets.main { java.srcDir(versionado) }

tasks.register<GerarJooq>("gerarJooq") {
    description = "Regenera as classes jOOQ versionadas a partir das migrações."
    migracoes.set(dirMigracoes)
    imagemPostgres.set(imagemPostgresJooq)
    pacote.set(pacoteJooq)
    destino.set(versionado)
    outputs.upToDateWhen { false }
}

val gerarParaConferir = tasks.register<GerarJooq>("gerarJooqParaConferir") {
    migracoes.set(dirMigracoes)
    imagemPostgres.set(imagemPostgresJooq)
    pacote.set(pacoteJooq)
    destino.set(layout.buildDirectory.dir("jooq-conferencia"))
}

val conferirJooq = tasks.register("conferirJooq") {
    description = "Falha se as classes jOOQ versionadas não correspondem às migrações."
    val gerado = gerarParaConferir.flatMap { it.destino }
    inputs.dir(gerado)
    inputs.dir(versionado)
    doLast {
        val a = gerado.get().asFile
        val b = versionado.asFile
        fun conteudo(raiz: java.io.File) = raiz.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(raiz).path to it.readText() }
        if (conteudo(a) != conteudo(b)) {
            throw GradleException("Classes jOOQ desatualizadas: rode ./ci.sh :financeiro:persistencia:gerarJooq")
        }
    }
}
tasks.check { dependsOn(conferirJooq) }
