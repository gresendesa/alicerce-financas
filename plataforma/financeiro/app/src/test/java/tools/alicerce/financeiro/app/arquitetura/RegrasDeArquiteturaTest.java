package tools.alicerce.financeiro.app.arquitetura;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = RegrasDeArquiteturaTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class RegrasDeArquiteturaTest {

    static final String BASE = "tools.alicerce";

    @ArchTest
    static final ArchRule dominioPuro = RegrasDeArquitetura.dominioPuro(BASE);

    @ArchTest
    static final ArchRule convencoesPuras = RegrasDeArquitetura.convencoesPuras(BASE);

    @ArchTest
    static final ArchRule adaptadoresSoChamamAplicacao = RegrasDeArquitetura.adaptadoresSoChamamAplicacao(BASE);

    @ArchTest
    static final ArchRule adaptadoresIndependentes = RegrasDeArquitetura.adaptadoresIndependentes(BASE);

    @ArchTest
    static final ArchRule modulosIsolados = RegrasDeArquitetura.modulosIsolados(BASE);

    @ArchTest
    static final ArchRule escritaSoComOperacao = RegrasDeArquitetura.escritaSoComOperacao(BASE);

    @ArchTest
    static final ArchRule portaDoRegistradorSoNoRegistrador = RegrasDeArquitetura.portaDoRegistradorSoNoRegistrador(BASE);
}
