package tools.alicerce.convencoes.vista;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O catálogo fixo e auditado de componentes de uma vista. Mudar este arquivo é mudar a
 * superfície de segurança: todo componente novo exige o desenho no renderizador e o teste de
 * acessibilidade. As propriedades obrigatórias de rótulo e legenda são a acessibilidade embutida.
 */
public final class CatalogoVista {

    public static final String ID = "alicerce/v1";

    /** Como o valor de uma propriedade pode ser escrito. */
    enum Tipo {
        /** Texto fixo, sem dígitos nem marcação. */
        TEXTO,
        /** Só por caminho: é por aqui que números e ids entram na vista. */
        VALOR,
        /** Texto fixo ou caminho. */
        ROTULO,
        /** Caminho para uma lista. */
        LISTA,
        /** Nome de um campo das linhas de uma lista. */
        CAMPO,
        /** Uma das opções da propriedade. */
        OPCAO,
        /** Ids de outros componentes. */
        FILHOS,
        /** Lista de objetos com propriedades próprias. */
        ITENS
    }

    record Propriedade(Tipo tipo, boolean obrigatoria, Set<String> opcoes, Map<String, Propriedade> itens) {
        static Propriedade exigida(Tipo tipo) {
            return new Propriedade(tipo, true, Set.of(), Map.of());
        }

        static Propriedade opcional(Tipo tipo) {
            return new Propriedade(tipo, false, Set.of(), Map.of());
        }

        static Propriedade opcao(String... opcoes) {
            return new Propriedade(Tipo.OPCAO, false, Set.of(opcoes), Map.of());
        }

        static Propriedade itens(Map<String, Propriedade> itens) {
            return new Propriedade(Tipo.ITENS, true, Set.of(), itens);
        }
    }

    private static final Propriedade FORMATO = Propriedade.opcao("texto", "dinheiro", "data", "data_hora", "numero");

    private static final Map<String, Map<String, Propriedade>> COMPONENTES = Map.of(
            "pagina", Map.of(
                    "titulo", Propriedade.exigida(Tipo.ROTULO),
                    "filhos", Propriedade.exigida(Tipo.FILHOS)),
            "cartao", Map.of(
                    "titulo", Propriedade.exigida(Tipo.ROTULO),
                    "filhos", Propriedade.exigida(Tipo.FILHOS)),
            "texto", Map.of(
                    "valor", Propriedade.exigida(Tipo.ROTULO),
                    "estilo", Propriedade.opcao("normal", "aviso")),
            "totais", Map.of(
                    "rotulo", Propriedade.exigida(Tipo.TEXTO),
                    "itens", Propriedade.itens(Map.of(
                            "rotulo", Propriedade.exigida(Tipo.TEXTO),
                            "valor", Propriedade.exigida(Tipo.VALOR),
                            "formato", FORMATO))),
            "lista", Map.of(
                    "rotulo", Propriedade.exigida(Tipo.TEXTO),
                    "origem", Propriedade.exigida(Tipo.LISTA),
                    "campo_titulo", Propriedade.exigida(Tipo.CAMPO),
                    "campo_detalhe", Propriedade.opcional(Tipo.CAMPO),
                    "campo_url", Propriedade.opcional(Tipo.CAMPO)),
            "tabela", Map.of(
                    "legenda", Propriedade.exigida(Tipo.TEXTO),
                    "origem", Propriedade.exigida(Tipo.LISTA),
                    // A linha cujo campo é verdadeiro sai destacada; a tabela precisa dizer o mesmo em texto (WCAG).
                    "campo_destaque", Propriedade.opcional(Tipo.CAMPO),
                    "colunas", Propriedade.itens(Map.of(
                            "titulo", Propriedade.exigida(Tipo.TEXTO),
                            "campo", Propriedade.exigida(Tipo.CAMPO),
                            "campo_url", Propriedade.opcional(Tipo.CAMPO),
                            "formato", FORMATO))),
            "grafico", Map.of(
                    "rotulo", Propriedade.exigida(Tipo.TEXTO),
                    "origem", Propriedade.exigida(Tipo.LISTA),
                    "campo_rotulo", Propriedade.exigida(Tipo.CAMPO),
                    "campo_valor", Propriedade.exigida(Tipo.CAMPO),
                    "formato", FORMATO),
            "link_curto", Map.of(
                    "rotulo", Propriedade.exigida(Tipo.TEXTO),
                    "referencia", Propriedade.exigida(Tipo.VALOR),
                    "url", Propriedade.exigida(Tipo.VALOR)),
            "link", Map.of(
                    "rotulo", Propriedade.exigida(Tipo.TEXTO),
                    "url", Propriedade.exigida(Tipo.VALOR)));

    private CatalogoVista() {}

    /** As propriedades do componente; nulo se ele não está no catálogo. */
    static Map<String, Propriedade> propriedades(String componente) {
        return COMPONENTES.get(componente);
    }

    /** Os nomes dos componentes, ordenados, para as mensagens e o esquema das tools. */
    public static List<String> nomes() {
        return COMPONENTES.keySet().stream().sorted().toList();
    }
}
