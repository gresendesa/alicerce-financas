package tools.alicerce.financeiro.aplicacao;

import static tools.alicerce.financeiro.aplicacao.TelaPadrao.coluna;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.colunaLink;
import static tools.alicerce.financeiro.aplicacao.TelaPadrao.mapa;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.Chamador;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * O {@link Desfazer} para a interface web: os mesmos dois passos e a mesma auditoria, com as
 * saídas em telas padrão. Fica fora de {@link Vistas}, que só lê e também atende o assistente. O token de
 * confirmação sai ao lado da vista, nunca em {@code dados}.
 */
@Component
public class TelasDesfazer {

    /** A tela da prévia e o que a casca devolve para confirmar. */
    public record Previa(Vista vista, String confirmacao, String validaAte) {}

    private static final TypeReference<List<Map<String, Object>>> LISTA = new TypeReference<>() {};

    private final JsonMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private final Desfazer desfazer;
    private final ObterContexto obterContexto;

    TelasDesfazer(Desfazer desfazer, ObterContexto obterContexto) {
        this.desfazer = desfazer;
        this.obterContexto = obterContexto;
    }

    public Previa previa(Chamador chamador, String codigo) {
        ReversaoRegistrada reversao = desfazer.executar(chamador, new Desfazer.Entrada(null, alvo(codigo), null, null)).valor();
        Map<String, Object> dados = dados(chamador, reversao);
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>(List.of(tela.texto("Nada mudou ainda. Confira o que vai acontecer e confirme no fim da página.", "aviso")));
        filhos.addAll(corpo(tela, dados, "Vai voltar a valer", "Vai ser estornado", "Saldos depois de desfazer"));
        return new Previa(tela.pagina("Prévia do desfazer", filhos), reversao.confirmacao(), reversao.confirmacaoValidaAte());
    }

    public Vista aplicar(Chamador chamador, String codigo, String confirmacao, String chave) {
        ResultadoEscrita<ReversaoRegistrada> resultado = desfazer.executar(chamador, new Desfazer.Entrada(chave, alvo(codigo), null, confirmacao));
        Map<String, Object> dados = dados(chamador, resultado.valor());
        dados.put("registro", mapa("id", resultado.operacao().referencia().id(), "url", resultado.operacao().url()));
        TelaPadrao tela = new TelaPadrao(dados);
        List<String> filhos = new ArrayList<>(List.of(tela.texto("Pronto. Nada foi apagado: o histórico mostra o que aconteceu, e desfazer esta operação refaz tudo.", "normal"),
                tela.enderecoCurto("/registro/id", "/registro/url")));
        filhos.addAll(corpo(tela, dados, "Voltou a valer", "Estornado", "Saldos agora"));
        return tela.pagina("Operação desfeita", filhos);
    }

    private static String alvo(String codigo) {
        return "o-" + codigo;
    }

    private Map<String, Object> dados(Chamador chamador, ReversaoRegistrada r) {
        return mapa("moeda", obterContexto.executar(chamador).moeda(), "operacoes", lista(r.operacoes()),
                "estornados", lista(r.lancamentosEstornados()), "restabelecidos", lista(r.lancamentosRestabelecidos()),
                "restauradas", lista(r.versoesRestauradas()), "arquivadas", lista(r.entidadesArquivadas()), "saldos", lista(r.saldos()),
                "mantida", r.estruturaMantida().stream().map(nome -> mapa("nome", nome)).toList(),
                "externos", r.efeitosExternos().stream().map(efeito -> mapa("efeito", efeito)).toList());
    }

    private List<Map<String, Object>> lista(Object valor) {
        return json.convertValue(valor, LISTA);
    }

    private static List<String> corpo(TelaPadrao tela, Map<String, Object> dados, String restabelecidos, String estornados, String saldos) {
        List<String> filhos = new ArrayList<>();
        filhos.add(tela.tabela("Operações revertidas", "/operacoes", List.of(colunaLink("Operação", "resumo", "url"), coluna("Capacidade", "capacidade", "texto"))));
        secao(tela, dados, filhos, "estornados", estornados, List.of(colunaLink("Lançamento", "descricao", "url"), coluna("Valor", "valor", "dinheiro")));
        secao(tela, dados, filhos, "restabelecidos", restabelecidos, List.of(colunaLink("Lançamento", "descricao", "url"), coluna("Valor", "valor", "dinheiro")));
        secao(tela, dados, filhos, "restauradas", "Volta à versão anterior", List.of(coluna("Tipo", "entidade", "texto"), coluna("Nome", "nome", "texto")));
        secao(tela, dados, filhos, "arquivadas", "Arquivado", List.of(coluna("Tipo", "entidade", "texto"), coluna("Nome", "nome", "texto")));
        secao(tela, dados, filhos, "mantida", "Estrutura mantida, que outras operações podem usar", List.of(coluna("Nome", "nome", "texto")));
        secao(tela, dados, filhos, "externos", "O que não se desfaz", List.of(coluna("Efeito", "efeito", "texto")));
        secao(tela, dados, filhos, "saldos", saldos, List.of(coluna("Conta", "conta", "texto"), coluna("Saldo", "saldo", "dinheiro")));
        return filhos;
    }

    private static void secao(TelaPadrao tela, Map<String, Object> dados, List<String> filhos, String chave, String legenda, List<Map<String, Object>> colunas) {
        if (!((List<?>) dados.get(chave)).isEmpty()) {
            filhos.add(tela.tabela(legenda, "/" + chave, colunas));
        }
    }
}
