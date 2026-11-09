package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/**
 * Um fato registrado: imutável, com movimentos que somam zero em cada moeda.
 *
 * @param estorna o lançamento que este estorna; {@code null} se não é um estorno
 * @param original como a compra aconteceu em outra moeda, ou o lado estrangeiro de um câmbio entre contas;
 *     {@code null} se foi tudo numa moeda só
 * @param precoAVista o preço sem juros, só para consulta; {@code null} em compra sem juros
 * @param procedencia se nasceu no sistema ou veio de fora; imutável
 * @param contaHistorica a conta a que um fato exógeno pertence, sem mexer nela; {@code null} se não há
 * @param aporte a meta que este lançamento aporta ou resgata; só em {@link TipoLancamento#APORTE_META}
 */
public record Lancamento(UUID id, TipoLancamento tipo, LocalDate data, String descricao, String contraparte,
        String observacao, UUID meioPagamento, OrigemLancamento origem, String cnpjEstabelecimento,
        List<Movimento> movimentos, List<ItemLancamento> itens, UUID estorna, ValorOriginal original,
        Dinheiro precoAVista, Procedencia procedencia, UUID contaHistorica, AporteMeta aporte) {

    public Lancamento {
        movimentos = List.copyOf(movimentos);
        itens = List.copyOf(itens);
        if ((tipo == TipoLancamento.APORTE_META) != (aporte != null)) {
            throw new IllegalArgumentException("Só aporte em meta tem meta");
        }
        exigirSomaZeroPorMoeda(movimentos);
    }

    private static void exigirSomaZeroPorMoeda(List<Movimento> movimentos) {
        Map<Currency, Long> somas = new HashMap<>();
        movimentos.forEach(m -> somas.merge(m.valor().moeda(), m.valor().centavos(), Long::sum));
        somas.forEach((moeda, soma) -> {
            if (soma != 0) {
                throw new IllegalArgumentException("Lançamento não soma zero em " + moeda + ": " + soma + " centavos");
            }
        });
    }

    public Lancamento(UUID id, TipoLancamento tipo, LocalDate data, String descricao, String contraparte,
            String observacao, UUID meioPagamento, OrigemLancamento origem, String cnpjEstabelecimento,
            List<Movimento> movimentos, List<ItemLancamento> itens) {
        this(id, tipo, data, descricao, contraparte, observacao, meioPagamento, origem, cnpjEstabelecimento, movimentos,
                itens, null, null, null, Procedencia.ENDOGENO, null, null);
    }

    public Lancamento comOriginal(ValorOriginal novo) {
        return new Lancamento(id, tipo, data, descricao, contraparte, observacao, meioPagamento, origem, cnpjEstabelecimento,
                movimentos, itens, estorna, novo, precoAVista, procedencia, contaHistorica, aporte);
    }

    public Lancamento comPrecoAVista(Dinheiro novo) {
        return new Lancamento(id, tipo, data, descricao, contraparte, observacao, meioPagamento, origem, cnpjEstabelecimento,
                movimentos, itens, estorna, original, novo, procedencia, contaHistorica, aporte);
    }

    public Lancamento comProcedencia(Procedencia nova, UUID conta) {
        return new Lancamento(id, tipo, data, descricao, contraparte, observacao, meioPagamento, origem, cnpjEstabelecimento,
                movimentos, itens, estorna, original, precoAVista, nova, conta, aporte);
    }

    /** Uma transferência que aporta numa meta ou resgata dela. */
    public Lancamento comoAporte(AporteMeta novo) {
        return new Lancamento(id, TipoLancamento.APORTE_META, data, descricao, contraparte, observacao, meioPagamento, origem,
                cnpjEstabelecimento, movimentos, itens, estorna, original, precoAVista, procedencia, contaHistorica, novo);
    }

    /** O estorno deste lançamento: os movimentos invertidos, na mesma data, para o par se anular no período. */
    public Lancamento estorno(UUID novoId, String motivo) {
        List<Movimento> invertidos = movimentos.stream().map(Movimento::invertido).toList();
        return new Lancamento(novoId, TipoLancamento.ESTORNO, data, "Estorno de " + descricao, contraparte, motivo,
                meioPagamento, origem, null, invertidos, List.of(), id, original, precoAVista, procedencia, contaHistorica, null);
    }

    /** Quanto o lançamento movimentou. */
    public Dinheiro total() {
        return total(movimentos, original);
    }

    /**
     * A soma das entradas. Num câmbio, só o lado que não é a moeda original, para nunca somar duas moedas.
     *
     * @param original a moeda original do lançamento, ou {@code null}
     */
    public static Dinheiro total(List<Movimento> movimentos, ValorOriginal original) {
        Currency excluida = original == null ? null : original.moeda();
        List<Dinheiro> entradas = movimentos.stream().map(Movimento::valor).filter(v -> !v.ehNegativo() && !v.moeda().equals(excluida)).toList();
        return entradas.stream().reduce(Dinheiro::somar).orElseThrow();
    }

    /** Dinheiro entre contas de moedas diferentes: o lado estrangeiro está em {@link #original()} e nos movimentos. */
    public boolean ehCambio() {
        return original != null && movimentos.stream().anyMatch(m -> m.valor().moeda().equals(original.moeda()));
    }

    /** Em quantas parcelas a compra foi feita; 1 se à vista. */
    public int parcelas() {
        return movimentos.stream().map(Movimento::totalParcelas).filter(java.util.Objects::nonNull).findFirst().orElse(1);
    }
}
