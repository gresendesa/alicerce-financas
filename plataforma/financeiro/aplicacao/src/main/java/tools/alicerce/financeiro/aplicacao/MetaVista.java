package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Uma meta nas saídas. Todo número sai do domínio: o progresso é a soma dos aportes menos os resgates registrados, e o
 * aporte mensal necessário é o que falta dividido pelos meses até a data alvo.
 *
 * @param prioridade de 1 (a mais importante) a 5
 * @param situacao {@code ativa}, {@code concluida} ou {@code encerrada}
 * @param contaReservada o nome da conta separada para a meta; ausente se não há
 * @param progresso aportes menos resgates
 * @param falta o alvo menos o progresso, nunca negativo
 * @param percentual o progresso sobre o alvo, em %, de 0 a 100
 * @param mesesRestantes os meses civis até a data alvo, o de hoje e o da data inclusive; ausente sem data alvo
 * @param aporteMensalNecessario quanto aportar por mês, todo mês, para chegar na data; ausente sem data alvo
 * @param prazoVencido a data alvo já passou e ainda falta
 * @param aportes só na consulta de uma meta: cada aporte e resgate vigente
 */
public record MetaVista(String id, String url, String nome, List<String> aliases, String valorAlvo, String moeda, String dataAlvo,
        int prioridade, String situacao, String contaReservada, String contaReservadaId, String observacao, String progresso, String falta,
        String percentual, Integer mesesRestantes, String aporteMensalNecessario, boolean prazoVencido, boolean alcancada,
        List<AporteVisto> aportes) {

    /**
     * @param tipo {@code aporte} ou {@code resgate}
     * @param valor sem sinal: o tipo diz para que lado
     */
    public record AporteVisto(String id, String url, String data, String descricao, String tipo, String valor) {}

    MetaVista comAportes(List<AporteVisto> lista) {
        return new MetaVista(id, url, nome, aliases, valorAlvo, moeda, dataAlvo, prioridade, situacao, contaReservada, contaReservadaId,
                observacao, progresso, falta, percentual, mesesRestantes, aporteMensalNecessario, prazoVencido, alcancada, lista);
    }
}
