package tools.alicerce.financeiro.aplicacao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.alicerce.convencoes.vista.CatalogoVista;
import tools.alicerce.convencoes.vista.ValidadorVista;

/** Monta a descrição padrão de uma tela, que passa pelo mesmo validador das vistas compostas. */
final class TelaPadrao {

    private final List<Map<String, Object>> componentes = new ArrayList<>();
    private final Map<String, Object> dados;

    TelaPadrao(Map<String, Object> dados) {
        this.dados = dados;
    }

    static Map<String, Object> ref(String caminho) {
        return Map.of("caminho", caminho);
    }

    static Map<String, Object> mapa(Object... pares) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put((String) pares[i], pares[i + 1]);
        }
        return mapa;
    }

    /** Uma coluna de tabela. */
    static Map<String, Object> coluna(String titulo, String campo, String formato) {
        return mapa("titulo", titulo, "campo", campo, "formato", formato);
    }

    static Map<String, Object> colunaLink(String titulo, String campo, String campoUrl) {
        return mapa("titulo", titulo, "campo", campo, "campo_url", campoUrl);
    }

    static Map<String, Object> colunaLink(String titulo, String campo, String campoUrl, String formato) {
        return mapa("titulo", titulo, "campo", campo, "campo_url", campoUrl, "formato", formato);
    }

    static Map<String, Object> total(String rotulo, String caminho, String formato) {
        return mapa("rotulo", rotulo, "valor", ref(caminho), "formato", formato);
    }

    String componente(String tipo, Object... pares) {
        String id = "c" + componentes.size();
        Map<String, Object> componente = mapa("id", id, "componente", tipo);
        componente.putAll(mapa(pares));
        componentes.add(componente);
        return id;
    }

    String texto(Object valor, String estilo) {
        return componente("texto", "valor", valor, "estilo", estilo);
    }

    String totais(String rotulo, List<Map<String, Object>> itens) {
        return componente("totais", "rotulo", rotulo, "itens", itens);
    }

    String tabela(String legenda, String origem, List<Map<String, Object>> colunas) {
        return componente("tabela", "legenda", legenda, "origem", ref(origem), "colunas", colunas);
    }

    /** Uma tabela em que a linha com {@code campoDestaque} verdadeiro sai destacada; o texto da linha diz o mesmo. */
    String tabelaDestacada(String legenda, String origem, List<Map<String, Object>> colunas, String campoDestaque) {
        return componente("tabela", "legenda", legenda, "origem", ref(origem), "colunas", colunas, "campo_destaque", campoDestaque);
    }

    String cartao(String titulo, String... filhos) {
        return componente("cartao", "titulo", titulo, "filhos", Arrays.asList(filhos));
    }

    String enderecoCurto(String caminhoId, String caminhoUrl) {
        return componente("link_curto", "rotulo", "Endereço", "referencia", ref(caminhoId), "url", ref(caminhoUrl));
    }

    /** Fecha a tela com o título e os filhos da página, e valida. */
    Vista pagina(Object titulo, List<String> filhos) {
        String raiz = componente("pagina", "titulo", titulo, "filhos", filhos);
        ValidadorVista.validar(raiz, componentes, dados);
        return new Vista(CatalogoVista.ID, raiz, componentes, dados, null);
    }
}
