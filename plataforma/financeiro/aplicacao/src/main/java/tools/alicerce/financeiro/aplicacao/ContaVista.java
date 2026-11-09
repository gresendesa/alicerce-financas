package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Uma conta resolvida pelo id curto: os mesmos dados de {@code obter_contexto}, com o endereço. Uma conta arquivada,
 * que o contexto não lista, traz só o cadastro.
 *
 * @param saldo {@code null} em categorias, fontes de renda e contas arquivadas
 * @param marco desde quando o sistema responde pelo saldo; {@code null} sem saldo
 */
public record ContaVista(String id, String url, String nome, String tipo, List<String> aliases, String contaPai, String moeda,
        boolean arquivada, String saldo, String marco, String ultimaConferencia, String situacaoConferencia,
        Boolean conferenciaVencida) {}
