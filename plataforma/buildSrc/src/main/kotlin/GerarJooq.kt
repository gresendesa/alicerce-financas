import org.flywaydb.core.Flyway
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Gera as classes jOOQ do esquema: sobe um Postgres descartável, aplica as migrações Flyway e lê
 * o esquema resultante. Assim as consultas são verificadas contra o banco de verdade.
 */
abstract class GerarJooq : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val migracoes: DirectoryProperty

    @get:Input
    abstract val imagemPostgres: Property<String>

    @get:Input
    abstract val pacote: Property<String>

    @get:OutputDirectory
    abstract val destino: DirectoryProperty

    @TaskAction
    fun gerar() {
        PostgreSQLContainer(imagemPostgres.get()).use { banco ->
            banco.start()
            Flyway.configure(javaClass.classLoader)
                .dataSource(banco.jdbcUrl, banco.username, banco.password)
                .locations("filesystem:" + migracoes.get().asFile.absolutePath)
                .load()
                .migrate()

            val saida = destino.get().asFile
            saida.deleteRecursively()
            val configuracao = Configuration()
                .withJdbc(Jdbc()
                    .withDriver("org.postgresql.Driver")
                    .withUrl(banco.jdbcUrl).withUser(banco.username).withPassword(banco.password))
                .withGenerator(Generator()
                    .withDatabase(Database()
                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withInputSchema("public")
                        .withOutputSchemaToDefault(true)
                        .withExcludes("flyway_schema_history"))
                    .withGenerate(Generate()
                        .withDeprecated(false)
                        .withGeneratedAnnotation(false)
                        .withJavaTimeTypes(true)
                        .withComments(false)
                        // Caminhos implícitos de volta (to-many) colidem nas chaves que apontam
                        // para a própria tabela (estorno, desfeita_por, conta_pai) e não são usados.
                        .withImplicitJoinPathsToMany(false))
                    .withTarget(Target()
                        .withPackageName(pacote.get())
                        .withDirectory(saida.absolutePath)))
            val anterior = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = javaClass.classLoader
            try {
                GenerationTool.generate(configuracao)
            } finally {
                Thread.currentThread().contextClassLoader = anterior
            }
        }
    }
}
