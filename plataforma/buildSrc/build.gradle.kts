plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Geração das classes jOOQ a partir de um Postgres real (tarefa GerarJooq).
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.jooq.codegen)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.testcontainers.postgresql)
}
