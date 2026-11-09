package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * O cálculo de {@code cortes_necessarios}, como o assistente o vê e o retrato o guarda. Todos os números saem do
 * domínio, em texto com duas casas na moeda do dono.
 *
 * @param fim o último dia do período
 * @param saldoPartida a soma das contas líquidas (corrente, carteira, poupança) na moeda do dono, hoje
 * @param entradasConhecidas as ocorrências de renda em aberto até o fim
 * @param saidasConhecidas as ocorrências a pagar em aberto e o restante das faturas que vencem até o fim, atrasadas inclusive
 * @param variavelRestante a soma do restante esperado das categorias
 * @param saldoProjetado no último dia do período
 * @param distancia o saldo projetado menos o alvo: positiva é folga, negativa é déficit
 * @param folga o que sobra acima do alvo; zero com déficit
 * @param deficit o que falta para o alvo; zero com folga
 * @param naoCortaveis as saídas conhecidas, item por item: faturas (com as parcelas dentro) e ocorrências de compromisso
 * @param entradas as entradas conhecidas, item por item
 * @param categorias da que tem mais espaço de corte para a que tem menos
 * @param plano ausente sem histórico suficiente
 */
public record CortesVisto(String calculadoEm, String hoje, String fim, String moeda, String estimativa, Alvo alvo, String saldoPartida,
        List<ContaPartida> contasPartida, String entradasConhecidas, String saidasConhecidas, String variavelRestante, String saldoProjetado,
        String distancia, String folga, String deficit, List<ItemAgenda> naoCortaveis, List<ItemAgenda> entradas, List<CategoriaCorte> categorias,
        Plano plano, int mesesReferencia, int mesesPedidos, List<Ressalva> ressalvas, List<String> notas) {

    /**
     * @param tipo {@code piso_periodo} (a restrição declarada), {@code saldo_final} (um saldo avulso) ou {@code meta}
     * @param valor o saldo mínimo no fim do período; com meta, o piso mais o aporte do mês
     * @param operacaoDeclaracao em {@code piso_periodo} e em {@code meta} com piso: a operação que gravou a declaração usada
     * @param meta só com alvo meta
     */
    public record Alvo(String tipo, String valor, String data, String operacaoDeclaracao, MetaAlvo meta) {}

    /**
     * A meta de um alvo meta: o saldo do fim do mês tem de cobrir o piso e ainda o aporte deste mês.
     *
     * @param aporteMes o aporte mensal necessário até a data alvo, quando o pedido foi feito
     * @param piso o piso declarado, ou zero sem declaração
     */
    public record MetaAlvo(String id, String url, String nome, String aporteMes, String piso) {}

    public record ContaPartida(String id, String url, String nome, String tipo, String saldo) {}

    /**
     * @param gastoAteHoje o gasto variável do mês até hoje, sem compromissos
     * @param tipico o típico até o fim do período, na estimativa pedida
     * @param restanteEsperado o típico menos o já gasto, nunca negativo
     * @param piso o percentil 25 do que a categoria gastou no mesmo trecho do mês: abaixo disso não se corta
     * @param espacoCorte quanto o restante passa do piso
     * @param corte quanto o plano corta dela; zero sem plano ou sem déficit
     */
    public record CategoriaCorte(String id, String url, String nome, boolean essencial, String gastoAteHoje, String tipico,
            String restanteEsperado, String piso, String espacoCorte, String corte) {}

    /**
     * @param cortes só as categorias com corte, as não essenciais antes
     * @param coberto quanto do déficit o plano cobre
     * @param descoberto o déficit que nem todo o espaço cobre
     * @param precisaEssenciais o plano só fecha cortando também categorias essenciais
     */
    public record Plano(List<Corte> cortes, String coberto, String descoberto, String espacoTotal, boolean precisaEssenciais, String nota) {}

    public record Corte(String id, String url, String nome, boolean essencial, String corte, String restanteEsperado, String restanteComCorte) {}

    public record Ressalva(String codigo, String mensagem) {}
}
