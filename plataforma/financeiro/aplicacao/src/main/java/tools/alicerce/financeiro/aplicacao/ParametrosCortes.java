package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Os parâmetros de {@code cortes_necessarios} já resolvidos, como o retrato os guarda. O alvo e as essenciais ficam
 * aqui como valiam no pedido: a comparação com o cálculo de agora usa os mesmos, só com os dados novos.
 *
 * @param hoje a data de hoje no fuso do dono quando os cortes foram pedidos
 * @param fim o fim do período: o último dia do mês, ou a data de um saldo avulso
 * @param alvo {@code piso_periodo}, {@code saldo_final} ou {@code meta}
 * @param valorAlvo o saldo mínimo no fim do período, na moeda do dono; com meta, o piso mais o aporte do mês
 * @param estimativa {@code p80} ou {@code p50}
 * @param essenciais os ids das categorias essenciais declaradas
 * @param meta com alvo meta: o id curto da meta; nulo nos outros alvos
 * @param nomeMeta com alvo meta: o nome dela no pedido
 * @param aporteMeta com alvo meta: o aporte mensal necessário no pedido
 * @param piso com alvo meta: o piso declarado no pedido, ou zero sem declaração
 */
public record ParametrosCortes(String hoje, String fim, String alvo, String valorAlvo, String estimativa, List<String> essenciais, String meta,
        String nomeMeta, String aporteMeta, String piso) {

    public ParametrosCortes(String hoje, String fim, String alvo, String valorAlvo, String estimativa, List<String> essenciais) {
        this(hoje, fim, alvo, valorAlvo, estimativa, essenciais, null, null, null, null);
    }
}
