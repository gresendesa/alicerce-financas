package tools.alicerce.convencoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenciaCurtaTest {

    @Test
    void geraSeisCaracteresDoAlfabetoSemAmbiguas() {
        Random aleatorio = new Random(42);
        for (int i = 0; i < 1000; i++) {
            ReferenciaCurta ref = ReferenciaCurta.gerar("fin", "t", aleatorio);
            assertThat(ref.codigo()).hasSize(6).doesNotContain("I", "L", "O", "U");
        }
    }

    @Test
    void formasDeEscrita() {
        ReferenciaCurta ref = new ReferenciaCurta("fin", "t", "8K3QZ2");
        assertThat(ref.id()).isEqualTo("t-8K3QZ2");
        assertThat(ref.idCompleto()).isEqualTo("fin:t-8K3QZ2");
        assertThat(ref.caminho()).isEqualTo("/fin/t/8K3QZ2");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "t-8K3QZ2", "fin:t-8K3QZ2", "t-8k3qz2", " t-8K3QZ2 ",
        "https://app.alicerce.tools/fin/t/8K3QZ2", "/fin/t/8K3QZ2/"})
    void leTudoQueOAssistentePodeColar(String texto) {
        assertThat(ReferenciaCurta.ler(texto, "fin")).contains(new ReferenciaCurta("fin", "t", "8K3QZ2"));
    }

    @Test
    void corrigeTrocasComunsAoDitar() {
        assertThat(ReferenciaCurta.ler("o-7RM2KO", "fin")).contains(new ReferenciaCurta("fin", "o", "7RM2K0"));
        assertThat(ReferenciaCurta.ler("t-IL3QZ2", "fin")).contains(new ReferenciaCurta("fin", "t", "113QZ2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pix Itaú", "t-8K3QZ", "t-8K3QZ22", "8K3QZ2", "t-8K3QU2", ""})
    void textoSemFormaDeReferenciaNaoEhReferencia(String texto) {
        assertThat(ReferenciaCurta.ler(texto, "fin")).isEmpty();
    }

    @Test
    void referenciaDeOutroModuloEhModuloErrado() {
        assertThatThrownBy(() -> ReferenciaCurta.ler("plan:m-X2N8BV", "fin"))
                .isInstanceOfSatisfying(ErroTipado.class,
                        e -> assertThat(e.codigo()).isEqualTo(CodigoErro.MODULO_ERRADO));
    }
}
