package tools.alicerce.financeiro.adaptador.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.alicerce.financeiro.aplicacao.Contexto;

/** Saída de {@code obter_contexto}, com os nomes em {@code snake_case} da interface. */
record SaidaContexto(
        @JsonProperty("conta_conectada") SaidaContaConectada contaConectada,
        String fuso,
        String moeda,
        List<SaidaConta> contas,
        List<SaidaCartao> cartoes,
        @JsonProperty("meios_pagamento") List<SaidaMeio> meiosPagamento,
        List<Contexto.AmbitoContexto> ambitos,
        List<SaidaDeclaracao> declaracoes,
        List<SaidaMeta> metas,
        List<SaidaCompromisso> compromissos) {

    record SaidaContaConectada(String usuario, String cliente, @JsonProperty("conta_de_teste") boolean contaDeTeste, String aviso) {}

    /** Uma meta, com os números do servidor: progresso, quanto falta e quanto aportar por mês até a data alvo. */
    record SaidaMeta(String id, String url, String nome, List<String> aliases, @JsonProperty("valor_alvo") String valorAlvo, String moeda,
            @JsonProperty("data_alvo") String dataAlvo, int prioridade, String situacao, @JsonProperty("conta_reservada") String contaReservada,
            String progresso, String falta, String percentual, @JsonProperty("meses_restantes") Integer mesesRestantes,
            @JsonProperty("aporte_mensal_necessario") String aporteMensalNecessario, @JsonProperty("prazo_vencido") boolean prazoVencido,
            boolean alcancada) {}

    record SaidaCompromisso(String id, String nome, String direcao, String tipo, String referencia, String valor,
            @JsonProperty("proximo_vencimento") String proximoVencimento, int atrasadas, @JsonProperty("valor_restante") String valorRestante) {}

    record SaidaConta(String id, String nome, String tipo, List<String> aliases,
            @JsonProperty("conta_pai") String contaPai, String saldo, String marco, String moeda,
            @JsonProperty("ultima_conferencia") String ultimaConferencia, @JsonProperty("situacao_conferencia") String situacaoConferencia,
            @JsonProperty("conferencia_vencida") Boolean conferenciaVencida) {}

    record SaidaCartao(String id, String nome, @JsonProperty("dia_fechamento") int diaFechamento,
            @JsonProperty("dia_vencimento") int diaVencimento, String limite, @JsonProperty("limite_disponivel") String limiteDisponivel,
            @JsonProperty("conta_pagamento") String contaPagamento, @JsonProperty("fatura_atual") SaidaFatura faturaAtual) {}

    /** @param total só o que já entrou nesta fatura */
    record SaidaFatura(String id, String periodo, @JsonProperty("fecha_em") String fechaEm, @JsonProperty("vence_em") String venceEm,
            String situacao, String total, String pago, String restante) {}

    /** Uma declaração vigente do perfil; só os campos do tipo vêm preenchidos. */
    record SaidaDeclaracao(String tipo, @JsonProperty("vigente_desde") String vigenteDesde, String periodo,
            @JsonProperty("saldo_minimo") String saldoMinimo, @JsonProperty("contas_pagas") Boolean contasPagas, List<SaidaCategoria> categorias,
            String valor, @JsonProperty("meses_de_gasto") Integer mesesDeGasto, @JsonProperty("valor_equivalente") String valorEquivalente,
            @JsonProperty("gasto_mensal_tipico") String gastoMensalTipico, @JsonProperty("meses_gasto_tipico") List<String> mesesGastoTipico,
            String percentual, List<SaidaMetaDeclarada> metas, String texto, String ressalva, String observacao, String operacao) {}

    record SaidaCategoria(String id, String nome, boolean arquivada) {}

    record SaidaMetaDeclarada(String id, String nome, @JsonProperty("data_alvo") String dataAlvo, String situacao) {}

    record SaidaMeio(String id, String nome, String tipo, List<String> aliases, String conta, boolean padrao,
            @JsonProperty("ambito_padrao") String ambitoPadrao) {}

    static SaidaContexto de(Contexto contexto) {
        List<SaidaConta> contas = contexto.contas().stream()
                .map(c -> new SaidaConta(c.id(), c.nome(), c.tipo(), c.aliases(), c.contaPai(), c.saldo(), c.marco(), c.moeda(),
                        c.ultimaConferencia(), c.situacaoConferencia(), c.conferenciaVencida()))
                .toList();
        List<SaidaMeio> meios = contexto.meiosPagamento().stream()
                .map(m -> new SaidaMeio(m.id(), m.nome(), m.tipo(), m.aliases(), m.conta(), m.padrao(), m.ambitoPadrao()))
                .toList();
        List<SaidaCartao> cartoes = contexto.cartoes().stream().map(c -> {
            var f = c.faturaAtual();
            return new SaidaCartao(c.id(), c.nome(), c.diaFechamento(), c.diaVencimento(), c.limite(), c.limiteDisponivel(), c.contaPagamento(),
                    new SaidaFatura(f.id(), f.periodo(), f.fechaEm(), f.venceEm(), f.situacao(), f.total(), f.pago(), f.restante()));
        }).toList();
        List<SaidaDeclaracao> declaracoes = contexto.declaracoes().stream().map(d -> new SaidaDeclaracao(d.tipo(), d.vigenteDesde(),
                d.periodo(), d.saldoMinimo(), d.contasPagas(), d.categorias() == null ? null
                        : d.categorias().stream().map(c -> new SaidaCategoria(c.id(), c.nome(), c.arquivada())).toList(),
                d.valor(), d.mesesDeGasto(), d.valorEquivalente(), d.gastoMensalTipico(), d.mesesGastoTipico(), d.percentual(),
                d.metas() == null ? null : d.metas().stream().map(m -> new SaidaMetaDeclarada(m.id(), m.nome(), m.dataAlvo(), m.situacao())).toList(),
                d.texto(), d.ressalva(), d.observacao(), d.operacao())).toList();
        var conectada = contexto.contaConectada();
        List<SaidaCompromisso> compromissos = contexto.compromissos().stream().map(c -> new SaidaCompromisso(c.id(), c.nome(), c.direcao(),
                c.tipo(), c.referencia(), c.valor(), c.proximoVencimento(), c.atrasadas(), c.valorRestante())).toList();
        return new SaidaContexto(new SaidaContaConectada(conectada.usuario(), conectada.cliente(), conectada.contaDeTeste(), conectada.aviso()),
                contexto.fuso(), contexto.moeda(), contas, cartoes, meios,
                contexto.ambitos(), declaracoes, contexto.metas().stream().map(m -> new SaidaMeta(m.id(), m.url(), m.nome(), m.aliases(),
                        m.valorAlvo(), m.moeda(), m.dataAlvo(), m.prioridade(), m.situacao(), m.contaReservada(), m.progresso(), m.falta(),
                        m.percentual(), m.mesesRestantes(), m.aporteMensalNecessario(), m.prazoVencido(), m.alcancada())).toList(), compromissos);
    }
}
