package tools.alicerce.financeiro.app.arquitetura;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Prova que cada regra falha diante de uma violação proposital (pacote {@code arqfixture}). */
class RegrasDetectamViolacoesTest {

    private static final String FIXTURE = "tools.alicerce.financeiro.app.arquitetura.arqfixture";
    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(FIXTURE);

    static Stream<Arguments> regras() {
        return Stream.of(
                regra("dominioPuro", RegrasDeArquitetura::dominioPuro),
                regra("convencoesPuras", RegrasDeArquitetura::convencoesPuras),
                regra("adaptadoresSoChamamAplicacao", RegrasDeArquitetura::adaptadoresSoChamamAplicacao),
                regra("adaptadoresIndependentes", RegrasDeArquitetura::adaptadoresIndependentes),
                regra("modulosIsolados", RegrasDeArquitetura::modulosIsolados),
                regra("escritaSoComOperacao", RegrasDeArquitetura::escritaSoComOperacao),
                regra("portaDoRegistradorSoNoRegistrador", RegrasDeArquitetura::portaDoRegistradorSoNoRegistrador));
    }

    private static Arguments regra(String nome, Function<String, ArchRule> fabrica) {
        return Arguments.of(nome, fabrica);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("regras")
    void regraFalhaComViolacaoProposital(String nome, Function<String, ArchRule> fabrica) {
        assertThatThrownBy(() -> fabrica.apply(FIXTURE).check(CLASSES)).isInstanceOf(AssertionError.class);
    }
}
