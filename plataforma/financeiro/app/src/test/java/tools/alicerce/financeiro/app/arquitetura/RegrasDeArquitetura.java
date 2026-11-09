package tools.alicerce.financeiro.app.arquitetura;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * Regras de arquitetura do briefing da modelagem. Cada regra recebe o pacote base para poder ser
 * verificada também contra as classes-fixture que a violam de propósito.
 */
final class RegrasDeArquitetura {

    private RegrasDeArquitetura() {}

    /** {@code dominio} não importa Spring, jOOQ nem nenhum adaptador. */
    static ArchRule dominioPuro(String base) {
        return noClasses().that().resideInAPackage(base + "..dominio..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.jooq..",
                        base + "..adaptador..", base + "..persistencia..",
                        base + "..aplicacao..", base + "..app..")
                .because("o domínio é Java puro, sem Spring, jOOQ ou adaptadores");
    }

    /** {@code convencoes} é Java puro e não depende de nenhum outro módulo. */
    static ArchRule convencoesPuras(String base) {
        return noClasses().that().resideInAPackage(base + ".convencoes..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "org.jooq..")
                .orShould().dependOnClassesThat(resideInAnyPackage(base + "..")
                        .and(not(resideInAnyPackage(base + ".convencoes..")))
                        .as("outros módulos da plataforma"))
                .because("convenções são Java puro e comuns a todos os módulos");
    }

    /** Adaptadores só chamam {@code aplicacao}: nunca {@code persistencia} diretamente. */
    static ArchRule adaptadoresSoChamamAplicacao(String base) {
        return noClasses().that().resideInAPackage(base + "..adaptador..")
                .should().dependOnClassesThat().resideInAPackage(base + "..persistencia..")
                .because("adaptadores falam só com a camada de aplicação");
    }

    /** Um adaptador não usa o outro. */
    static ArchRule adaptadoresIndependentes(String base) {
        return slices().matching(base + "..adaptador.(*)..")
                .should().notDependOnEachOther()
                .because("adaptadores falam só com a camada de aplicação");
    }

    /** Um módulo de negócio nunca importa outro; o que é comum vive em convencoes (ou seguranca). */
    static ArchRule modulosIsolados(String base) {
        return slices().matching(base + ".(*)..")
                .should().notDependOnEachOther()
                .ignoreDependency(alwaysTrue(),
                        resideInAnyPackage(base + ".convencoes..", base + ".seguranca.."))
                .because("módulos compartilham convenções, nunca dados ou código de negócio");
    }

    /**
     * Quem escreve no banco recebe uma {@code OperacaoEmCurso}, que só o registrador cria: assim
     * nenhuma escrita acontece fora de uma operação, e toda operação grava seus efeitos. Escrita
     * dentro de lambda também conta (a lambda não recebe a operação): escreva fora dela.
     *
     * <p>Exceções: o próprio registrador e a auditoria ({@code RepositorioOperacoesJooq},
     * {@code RepositorioAuditoriaJooq}) e a criação do dono, que é identidade e não capacidade
     * ({@code RepositorioDonoJooq.garantir}).
     */
    static ArchRule escritaSoComOperacao(String base) {
        String operacao = base + ".financeiro.aplicacao.OperacaoEmCurso";
        return codeUnits().that().areDeclaredInClassesThat().resideInAPackage(base + "..persistencia..")
                .should(new ArchCondition<JavaCodeUnit>("receber OperacaoEmCurso quando escrevem no banco") {
                    @Override
                    public void check(JavaCodeUnit unidade, ConditionEvents eventos) {
                        if (EXCECOES_ESCRITA.contains(unidade.getOwner().getSimpleName())
                                || EXCECOES_ESCRITA.contains(unidade.getOwner().getSimpleName() + "." + unidade.getName())) {
                            return;
                        }
                        boolean escreve = unidade.getCallsFromSelf().stream().anyMatch(RegrasDeArquitetura::ehEscrita);
                        boolean recebeOperacao = unidade.getRawParameterTypes().stream()
                                .anyMatch(tipo -> tipo.getName().equals(operacao));
                        if (escreve && !recebeOperacao) {
                            eventos.add(SimpleConditionEvent.violated(unidade,
                                    unidade.getFullName() + " escreve no banco sem receber OperacaoEmCurso"));
                        }
                    }
                })
                .because("toda escrita passa pelo registrador de operações e gera efeito_operacao");
    }

    /** A porta do registrador só é usada por ele: ninguém grava operação, efeito ou auditoria por fora. */
    static ArchRule portaDoRegistradorSoNoRegistrador(String base) {
        String aplicacao = base + ".financeiro.aplicacao";
        return noClasses().that().resideOutsideOfPackage(base + "..persistencia..")
                .and().doNotHaveFullyQualifiedName(aplicacao + ".RegistradorOperacoes")
                .and().doNotHaveFullyQualifiedName(aplicacao + ".OperacaoEmCurso")
                .should().dependOnClassesThat().haveFullyQualifiedName(aplicacao + ".RepositorioOperacoes")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(aplicacao + ".RepositorioAuditoria")
                .because("operação, efeitos e auditoria só são gravados pelo registrador");
    }

    private static final Set<String> EXCECOES_ESCRITA = Set.of(
            "RepositorioOperacoesJooq", "RepositorioAuditoriaJooq", "RepositorioDonoJooq.garantir");

    private static final Set<String> ESCRITAS_DSL = Set.of(
            "insertInto", "update", "deleteFrom", "delete", "mergeInto", "truncate", "truncateTable",
            "execute", "query", "batch", "batchInsert", "batchUpdate", "batchStore", "batchDelete", "batchMerge",
            "executeInsert", "executeUpdate", "executeDelete");

    private static final Set<String> ESCRITAS_REGISTRO = Set.of("store", "insert", "update", "delete", "merge");

    private static boolean ehEscrita(JavaCall<?> chamada) {
        var dono = chamada.getTargetOwner();
        String nome = chamada.getName();
        return dono.isAssignableTo("org.jooq.DSLContext") && ESCRITAS_DSL.contains(nome)
                || dono.isAssignableTo("org.jooq.TableRecord") && ESCRITAS_REGISTRO.contains(nome)
                || dono.getPackageName().startsWith("java.sql")
                || dono.getPackageName().startsWith("org.springframework.jdbc");
    }
}
