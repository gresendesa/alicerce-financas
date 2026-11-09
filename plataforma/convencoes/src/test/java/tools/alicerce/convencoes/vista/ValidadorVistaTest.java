package tools.alicerce.convencoes.vista;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.alicerce.convencoes.ErroTipado;

class ValidadorVistaTest {

    private static final Map<String, Object> DADOS = Map.of(
            "total", "12.50",
            "linhas", List.of(Map.of("nome", "Mercado", "valor", "12.50")),
            "objeto", Map.of("a", "b"));

    private static Map<String, Object> pagina(String... filhos) {
        return Map.of("id", "r", "componente", "pagina", "titulo", "Contas", "filhos", List.of(filhos));
    }

    private static Map<String, Object> texto(String id, Object valor) {
        return Map.of("id", id, "componente", "texto", "valor", valor);
    }

    private static Map<String, Object> totais(Object valor) {
        return Map.of("id", "t", "componente", "totais", "rotulo", "Totais",
                "itens", List.of(Map.of("rotulo", "Total", "valor", valor, "formato", "dinheiro")));
    }

    private static void recusada(String mensagem, Map<?, ?>... componentes) {
        assertThatThrownBy(() -> ValidadorVista.validar("r", List.of(componentes), DADOS))
                .isInstanceOf(ErroTipado.class).hasMessageContaining(mensagem);
    }

    @Test
    void aceitaUmaVistaComTodoOCatalogo() {
        List<Object> componentes = new ArrayList<>(List.of(
                pagina("c", "l", "g", "lc", "ln"),
                Map.of("id", "c", "componente", "cartao", "titulo", Map.of("caminho", "/total"), "filhos", List.of("x", "t", "tb")),
                texto("x", "Sem pendências"),
                totais(Map.of("caminho", "/total")),
                Map.of("id", "tb", "componente", "tabela", "legenda", "Gastos", "origem", Map.of("caminho", "/linhas"),
                        "colunas", List.of(Map.of("titulo", "Valor", "campo", "valor", "formato", "dinheiro"))),
                Map.of("id", "l", "componente", "lista", "rotulo", "Itens", "origem", Map.of("caminho", "/linhas"),
                        "campo_titulo", "nome"),
                Map.of("id", "g", "componente", "grafico", "rotulo", "Gastos", "origem", Map.of("caminho", "/linhas"),
                        "campo_rotulo", "nome", "campo_valor", "valor"),
                Map.of("id", "lc", "componente", "link_curto", "rotulo", "Endereço", "referencia", Map.of("caminho", "/total"),
                        "url", Map.of("caminho", "/total")),
                Map.of("id", "ln", "componente", "link", "rotulo", "Próxima", "url", Map.of("caminho", "/total"))));

        assertThatCode(() -> ValidadorVista.validar("r", componentes, DADOS)).doesNotThrowAnyException();
    }

    @Test
    void recusaComponenteForaDoCatalogo() {
        recusada("fora do catálogo", pagina("x"), Map.of("id", "x", "componente", "iframe", "src", "https://mal.example"));
    }

    @Test
    void recusaPropriedadeForaDoCatalogo() {
        recusada("Propriedade fora do catálogo", pagina("x"),
                Map.of("id", "x", "componente", "texto", "valor", "Oi", "aoClicar", "alert()"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<b>oi</b>", "<script>alert()</script>", "a & b", "javascript:alert()", "data:text/html", "{{x}}"})
    void recusaMarcacaoEmTexto(String valor) {
        recusada("marcação", pagina("x"), texto("x", valor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Você gastou R$ 500", "Total 12", "٣ contas"})
    void recusaNumeroEscritoEmTexto(String valor) {
        recusada("número escrito à mão", pagina("x"), texto("x", valor));
    }

    @Test
    void recusaNumeroLiteralNoLugarDeCaminho() {
        recusada("só aceita", pagina("t"), totais("500.00"));
        recusada("só aceita", pagina("t"), totais(500));
        recusada("só aceita", pagina("x"), texto("x", 500));
    }

    @Test
    void recusaCaminhoSemOrigemNosDados() {
        recusada("sem origem no domínio", pagina("t"), totais(Map.of("caminho", "/inventado")));
        recusada("Caminho inválido", pagina("t"), totais(Map.of("caminho", "/../segredo")));
        recusada("valor simples", pagina("t"), totais(Map.of("caminho", "/objeto")));
    }

    @Test
    void recusaOrigemQueNaoELista() {
        recusada("uma lista", pagina("l"), Map.of("id", "l", "componente", "lista", "rotulo", "Itens",
                "origem", Map.of("caminho", "/total"), "campo_titulo", "nome"));
    }

    @Test
    void exigeOsRotulosDeAcessibilidade() {
        recusada("Falta a propriedade legenda", pagina("t"), Map.of("id", "t", "componente", "tabela",
                "origem", Map.of("caminho", "/linhas"), "colunas", List.of(Map.of("titulo", "Nome", "campo", "nome"))));
    }

    @Test
    void recusaArvoreQuebrada() {
        recusada("não existe", pagina("x"));
        recusada("Id repetido", pagina("x"), texto("x", "A"), texto("x", "B"));
        recusada("fora da árvore", pagina("x"), texto("x", "A"), texto("solto", "B"));
        recusada("raiz precisa ser", texto("r", "A"));
        recusada("dois pais ou em ciclo", pagina("c"),
                Map.of("id", "c", "componente", "cartao", "titulo", "A", "filhos", List.of("r")));
    }

    @Test
    void recusaVistaGrandeDemais() {
        List<Object> muitos = new ArrayList<>();
        for (int i = 0; i <= ValidadorVista.MAX_COMPONENTES; i++) {
            muitos.add(texto("c" + i, "A"));
        }
        assertThatThrownBy(() -> ValidadorVista.validar("r", muitos, DADOS)).hasMessageContaining("passa de");
    }
}
