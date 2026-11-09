package tools.alicerce.financeiro.aplicacao;

import static tools.alicerce.financeiro.aplicacao.TelaPadrao.coluna;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.ref;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.total;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.alicerce.convencoes.vista.CatalogoVista;
import tools.alicerce.convencoes.vista.ValidadorVista;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tela da prestação de contas: a descrição é montada uma vez, quando o retrato é gravado, e
 * guardada nele. A página pública e a interface do dono desenham essa mesma descrição, com os dados do retrato.
 */
final class TelaPrestacao {

    /**
     * O que o retrato de {@code prestar_contas} guarda: os dados e a descrição da tela.
     *
     * @param componentes a lista plana do catálogo, já validada
     */
    record Guardada(PrestacaoVista prestacao, String catalogo, String raiz, List<Map<String, Object>> componentes) {}

    private static final TypeReference<Map<String, Object>> MAPA = new TypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private TelaPrestacao() {}

    static Guardada montar(PrestacaoVista prestacao) {
        Map<String, Object> dados = dados(prestacao);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.texto("Gastos e entradas de um âmbito, publicados pelo dono. Esta página não mostra saldos nem contas.", "normal"));
        filhos.add(tela.totais("Período e totais", List.of(total("Âmbito", "/prestacao/ambito", "texto"), total("De", "/prestacao/de", "data"),
                total("Até", "/prestacao/ate", "data"), total("Gastos", "/prestacao/gastos", "dinheiro"),
                total("Entradas", "/prestacao/entradas", "dinheiro"), total("Lançamentos", "/prestacao/quantidade_lancamentos", "numero"),
                total("Link válido até", "/prestacao/valida_ate", "data"))));
        filhos.add(tela.cartao("Por categoria",
                tela.componente("grafico", "rotulo", "Gastos por categoria", "origem", ref("/prestacao/categorias"), "campo_rotulo", "nome",
                        "campo_valor", "gastos", "formato", "dinheiro"),
                tela.tabela("Por categoria", "/prestacao/categorias", List.of(coluna("Categoria", "nome", "texto"),
                        coluna("Gastos", "gastos", "dinheiro"), coluna("Entradas", "entradas", "dinheiro")))));
        filhos.add(tela.cartao("Lançamentos", tela.tabela("Lançamentos", "/prestacao/lancamentos", List.of(coluna("Data", "data", "data"),
                coluna("Tipo", "tipo", "texto"), coluna("Descrição", "descricao", "texto"), coluna("Estabelecimento", "estabelecimento", "texto"),
                coluna("Meio", "meio", "texto"), coluna("Valor", "valor", "dinheiro")))));
        if (prestacao.comprovantes() != null) {
            filhos.add(tela.cartao("Comprovantes",
                    tela.texto("Imagens enviadas pelo dono como prova de pagamento.", "normal"),
                    tela.componente("lista", "rotulo", "Comprovantes", "origem", ref("/prestacao/comprovantes"), "campo_titulo", "titulo",
                            "campo_url", "url")));
        }
        if (prestacao.notas() != null) {
            filhos.add(tela.cartao("Notas fiscais eletrônicas",
                    tela.texto("Qualquer pessoa pode conferir cada chave de acesso no portal da NFC-e do estado da compra.", "normal"),
                    tela.tabela("Chaves de acesso", "/prestacao/notas", List.of(coluna("Data", "data", "data"),
                            coluna("Descrição", "descricao", "texto"), coluna("Chave de acesso", "chave", "texto")))));
        }
        Vista vista = tela.pagina("Prestação de contas", filhos);
        return new Guardada(prestacao, vista.catalogo(), vista.raiz(), vista.componentes());
    }

    /** A descrição guardada com os dados guardados: nada é composto na hora. Valida de novo, como segunda camada. */
    static Vista abrir(Guardada guardada, PrestacaoVista prestacao) {
        if (!CatalogoVista.ID.equals(guardada.catalogo())) {
            throw new IllegalStateException("Retrato de prestação com catálogo desconhecido: " + guardada.catalogo());
        }
        Map<String, Object> dados = dados(prestacao);
        ValidadorVista.validar(guardada.raiz(), guardada.componentes(), dados);
        return new Vista(guardada.catalogo(), guardada.raiz(), guardada.componentes(), dados, null);
    }

    /** Em {@code snake_case}, como os outros retratos. */
    static String json(Object valor) {
        return JSON.writeValueAsString(valor);
    }

    static Guardada ler(String json) {
        return JSON.readValue(json, Guardada.class);
    }

    static ParametrosPrestacao parametros(String json) {
        return JSON.readValue(json, ParametrosPrestacao.class);
    }

    private static Map<String, Object> dados(PrestacaoVista prestacao) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("moeda", prestacao.moeda());
        dados.put("prestacao", JSON.convertValue(prestacao, MAPA));
        return dados;
    }
}
