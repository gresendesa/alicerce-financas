package tools.alicerce.convencoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdempotenciaTest {

    @Test
    void mesmaChamadaMesmoHashQualquerQueSejaAOrdemOuOsNulos() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("valor", new BigDecimal("45.0"));
        a.put("descricao", "Almoço");
        a.put("data", LocalDate.of(2026, 10, 29));
        Map<String, Object> b = new HashMap<>();
        b.put("data", LocalDate.of(2026, 10, 29));
        b.put("observacao", null);
        b.put("descricao", "Almoço");
        b.put("valor", new BigDecimal("45.00"));
        assertThat(HashArgumentos.de(a)).isEqualTo(HashArgumentos.de(b)).hasSize(64);
    }

    @Test
    void qualquerDiferencaMudaOHash() {
        String base = HashArgumentos.de(Map.of("valor", "45.00", "itens", List.of("a", "b")));
        assertThat(HashArgumentos.de(Map.of("valor", "45.01", "itens", List.of("a", "b")))).isNotEqualTo(base);
        assertThat(HashArgumentos.de(Map.of("valor", "45.00", "itens", List.of("b", "a")))).isNotEqualTo(base);
        // Texto e número não se confundem; aspas não escapam da string.
        assertThat(HashArgumentos.canonico(Map.of("x", "1"))).isNotEqualTo(HashArgumentos.canonico(Map.of("x", 1)));
        assertThat(HashArgumentos.canonico(Map.of("x", "a\",\"y\":\"b")))
                .isNotEqualTo(HashArgumentos.canonico(Map.of("x", "a", "y", "b")));
    }

    @Test
    void dinheiroEntraComAMoeda() {
        Map<String, Object> brl = Map.of("valor", Dinheiro.deCentavos(4500, Currency.getInstance("BRL")));
        Map<String, Object> usd = Map.of("valor", Dinheiro.deCentavos(4500, Currency.getInstance("USD")));
        assertThat(HashArgumentos.de(brl)).isNotEqualTo(HashArgumentos.de(usd));
    }

    @Test
    void doubleNaoTemFormaCanonica() {
        assertThatThrownBy(() -> HashArgumentos.de(Map.of("valor", 45.0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chaveObrigatoriaCurtaEVisivel() {
        assertThat(ChaveIdempotencia.ler("almoco-2026-10-29").valor()).isEqualTo("almoco-2026-10-29");
        assertThatThrownBy(() -> ChaveIdempotencia.ler(null))
                .isInstanceOfSatisfying(ErroTipado.class, e -> {
                    assertThat(e.codigo()).isEqualTo(CodigoErro.CAMPOS_FALTANDO);
                    assertThat(e.camposFaltando()).containsExactly("chave_idempotencia");
                });
        assertThatThrownBy(() -> ChaveIdempotencia.ler("x".repeat(65))).isInstanceOf(ErroTipado.class);
        assertThatThrownBy(() -> ChaveIdempotencia.ler("com espaço")).isInstanceOf(ErroTipado.class);
    }

    @Test
    void codigosComoOAssistenteVe() {
        assertThat(CodigoErro.VALIDACAO_FALHOU.codigo()).isEqualTo("validacao_falhou");
        assertThat(CodigoErro.ERRO_INTERNO.podeRepetir()).isTrue();
        assertThat(CodigoErro.CONFLITO_IDEMPOTENCIA.podeRepetir()).isFalse();
    }
}
