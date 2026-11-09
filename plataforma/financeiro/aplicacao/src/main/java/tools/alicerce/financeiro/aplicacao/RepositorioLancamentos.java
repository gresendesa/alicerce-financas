package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.FatoPeriodo;
import tools.alicerce.financeiro.dominio.Lancamento;
import tools.alicerce.financeiro.dominio.Movimento;
import tools.alicerce.financeiro.dominio.Procedencia;
import tools.alicerce.financeiro.dominio.TipoLancamento;
import tools.alicerce.financeiro.dominio.ValorOriginal;

/** Porta de persistência dos lançamentos. */
public interface RepositorioLancamentos {

    /** O bastante para mostrar ao dono um lançamento que já existe. */
    record Resumo(String codigo, String descricao) {}

    /**
     * A cadeia de estornos em que um lançamento está.
     *
     * @param raiz o lançamento original
     * @param ponta o último estorno, ou a própria raiz
     * @param tamanho quantos estornos há a partir da raiz: ímpar, a raiz está anulada
     * @param passosDoAlvo quantos estornos há depois do lançamento consultado
     * @param operacaoDaPonta a operação que gravou a ponta
     */
    record Cadeia(UUID raiz, UUID ponta, int tamanho, int passosDoAlvo, UUID operacaoDaPonta) {}

    /** Onde um cursor de lançamentos parou. */
    record Posicao(LocalDate data, OffsetDateTime registradoEm, UUID id) {}

    /** Filtros de {@code listar_lancamentos}, já resolvidos; nulo não filtra. */
    record Filtro(LocalDate de, LocalDate ate, Set<UUID> contas, UUID meio, String texto, Long valorMinimo,
            Long valorMaximo, boolean incluirAnulados, Posicao depoisDe, Procedencia procedencia) {}

    /** Um lançamento como as consultas o leem. */
    record Linha(UUID id, String codigo, TipoLancamento tipo, LocalDate data, String descricao, String contraparte,
            String observacao, UUID meio, String origem, boolean anulado, String estornaCodigo, String estornadoPorCodigo,
            String operacaoCodigo, OffsetDateTime registradoEm, List<Movimento> movimentos, ValorOriginal original,
            tools.alicerce.convencoes.Dinheiro precoAVista, Procedencia procedencia, UUID contaHistorica) {}

    void inserir(OperacaoEmCurso operacao, Lancamento lancamento);

    Optional<Lancamento> buscar(UUID dono, UUID id);

    Cadeia cadeia(UUID dono, UUID lancamento);

    /** Grava a chave da nota. Falso se ela já existe. */
    boolean inserirChaveFiscal(OperacaoEmCurso operacao, String chave, UUID lancamento);

    /** O lançamento vigente da nota; uma nota de lançamento anulado está livre. */
    Optional<Resumo> lancamentoDaChaveFiscal(UUID dono, String chave);

    /** Para onde a nota aponta, vigente ou não. */
    Optional<UUID> lancamentoApontadoPelaChave(UUID dono, String chave);

    /** A nota que aponta para o lançamento. */
    Optional<String> chaveDoLancamento(UUID dono, UUID lancamento);

    void moverChaveFiscal(OperacaoEmCurso operacao, String chave, UUID lancamento);

    /**
     * Lançamentos vigentes do mesmo tipo, data e meio, com o mesmo valor na conta, registrados nos
     * últimos minutos.
     */
    List<Resumo> parecidos(UUID dono, TipoLancamento tipo, LocalDate data, UUID meio, UUID conta, long valorCentavos,
            int minutos);

    long saldoCentavos(UUID conta);

    /** Saldo de todas as contas do dono com algum movimento. */
    Map<UUID, Long> saldosCentavos(UUID dono);

    /** Saldo de cada conta do dono com a soma dos movimentos até a data, inclusive; cada um na moeda da própria conta. */
    Map<UUID, Long> saldosCentavosAte(UUID dono, LocalDate ate);

    /** A última cotação vigente de cada moeda com data até {@code ate}: a que o dono registrou mais recentemente. */
    Map<java.util.Currency, tools.alicerce.financeiro.dominio.CalculoConversao.Cotacao> cotacoes(UUID dono, LocalDate ate);

    /** Até {@code limite} lançamentos, dos mais recentes aos mais antigos. */
    List<Linha> listar(UUID dono, Filtro filtro, int limite);

    Optional<Linha> linha(UUID dono, UUID id);

    /**
     * Os movimentos de gastos e ganhos vigentes com data no período, na regra de {@code listar_lancamentos}:
     * sem estornos nem lançamentos anulados.
     *
     * @param ambito só os movimentos deste âmbito; nulo não filtra
     */
    List<FatoPeriodo> fatosDoPeriodo(UUID dono, LocalDate de, LocalDate ate, UUID ambito);

    /** Como {@link #fatosDoPeriodo}, sem as compras no crédito, que contam pelo vencimento, e sem o que não passou pelas contas. */
    List<FatoPeriodo> fatosForaDoCredito(UUID dono, LocalDate de, LocalDate ate, UUID ambito);

    List<tools.alicerce.financeiro.dominio.ItemLancamento> itens(UUID lancamento);

    /**
     * Um ajuste de conferência vigente, pelo lado da conta conferida.
     *
     * @param centavos positivo: apareceu dinheiro que não estava registrado; na moeda da conta
     */
    record Ajuste(UUID lancamento, String codigo, LocalDate data, String descricao, long centavos, String moeda) {}

    /** Os ajustes de conferência vigentes com data no período. */
    List<Ajuste> ajustesDoPeriodo(UUID dono, LocalDate de, LocalDate ate);

    /** As operações donas dos lançamentos vigentes que usam estas contas. */
    Set<UUID> operacoesComConta(UUID conta);

    /** As operações de todo lançamento que tocou a conta, anulado ou não: nenhum movimento some do livro-razão. */
    Set<UUID> operacoesComMovimentos(UUID conta);

    Set<UUID> operacoesComAmbito(UUID ambito);

    Set<UUID> operacoesComMeio(UUID meio);

    /** A operação que gravou o lançamento. */
    UUID operacaoDe(UUID lancamento);

    /** Os ids curtos destes lançamentos. */
    Map<UUID, String> codigos(Collection<UUID> lancamentos);
}
