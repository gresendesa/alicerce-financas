package tools.alicerce.financeiro.aplicacao;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.alicerce.convencoes.ReferenciaCurta;
import tools.alicerce.financeiro.aplicacao.RepositorioHistorico.Operacao;
import tools.alicerce.financeiro.dominio.TipoReferencia;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A frase que descreve uma operação ao dono. Os valores vêm do resultado gravado, nunca dos argumentos. */
@Component
class ResumoOperacao {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Enderecos enderecos;

    ResumoOperacao(Enderecos enderecos) {
        this.enderecos = enderecos;
    }

    OperacaoVista ver(Operacao o) {
        String url = ReferenciaCurta.ler(o.codigo(), TipoReferencia.MODULO).map(enderecos::url).orElse(null);
        return new OperacaoVista(o.codigo(), url, o.capacidade(), resumo(o), o.lote(), o.cliente(), o.criadaEm().toString(),
                o.desfeita() ? "desfeita" : "aplicada");
    }

    String resumo(Operacao o) {
        JsonNode argumentos = json.readTree(o.argumentos());
        JsonNode resultado = o.resultado() == null ? json.createObjectNode() : json.readTree(o.resultado());
        return switch (o.capacidade()) {
            case "registrar_gasto" -> "gasto de " + dinheiro(resultado) + " em "
                    + primeiro(argumentos.path("categoria"), resultado.path("descricao"))
                    + ", " + resultado.path("meioPagamento").asString("");
            case "registrar_ganho" -> "ganho de " + dinheiro(resultado) + " de " + argumentos.path("categoria").asString("")
                    + ", em " + resultado.path("conta").asString("");
            case "registrar_transferencia" -> resultado.path("meta").isObject()
                    ? ("resgate".equals(resultado.path("meta").path("movimento").asString(null)) ? "resgate de " : "aporte de ") + dinheiro(resultado)
                            + " na meta " + resultado.path("meta").path("nome").asString("") + ", de " + resultado.path("de").asString("") + " para "
                            + resultado.path("ate").asString("")
                    : ("pagamento_fatura".equals(resultado.path("tipo").asString(null)) ? "pagamento de fatura de " : "transferência de ")
                    + dinheiro(resultado) + ", de " + resultado.path("de").asString("") + " para " + resultado.path("ate").asString("");
            case "corrigir_lancamento" -> ("anular".equals(resultado.path("acao").asString(null)) ? "anulação de " : "correção de ")
                    + resultado.path("lancamento").asString("") + ": " + argumentos.path("motivo").asString("");
            case "definir_conta" -> texto(argumentos, "conta ") + resultado.path("nome").asString("");
            case "definir_meio_pagamento" -> texto(argumentos, "meio de pagamento ") + resultado.path("nome").asString("");
            case "definir_ambito" -> texto(argumentos, "âmbito ") + resultado.path("nome").asString("");
            case "anexar_comprovante" -> resultado.path("comprovante").isString()
                    ? imagem(resultado) + " anexad" + genero(resultado) + " a " + resultado.path("lancamento").asString("")
                    : "link de envio para " + resultado.path("lancamento").asString("");
            case EnviarComprovante.CAPACIDADE -> imagem(resultado) + " enviad" + genero(resultado) + " para "
                    + resultado.path("lancamento").asString("");
            case ResumirPeriodo.CAPACIDADE -> "relatório " + resultado.path("retrato").asString("");
            case RitmoGastos.CAPACIDADE -> "ritmo de gastos " + resultado.path("retrato").asString("");
            case ConferirSaldo.CAPACIDADE -> "conferência de " + primeiro(resultado.path("fatura"), resultado.path("conta")) + ": "
                    + resultado.path("situacao").asString("") + ("conferida".equals(resultado.path("situacao").asString(null)) ? ""
                            : ", diferença de " + dinheiro(resultado.path("diferenca").asString("0"), resultado.path("moeda").asString("BRL")));
            case DefinirCompromisso.CAPACIDADE -> texto(argumentos, "compromisso ") + resultado.path("nome").asString("");
            case DefinirMeta.CAPACIDADE -> texto(argumentos, "meta ") + resultado.path("nome").asString("");
            case Declarar.CAPACIDADE -> "declaração " + resultado.path("declaracao").path("tipo").asString("");
            case CortesNecessarios.CAPACIDADE -> "cortes necessários " + resultado.path("retrato").asString("");
            case Projetar.CAPACIDADE -> "projeção " + resultado.path("retrato").asString("");
            case PrestarContas.CAPACIDADE -> resultado.path("revogado").asBoolean(false)
                    ? "revogação do link de prestação de contas " + resultado.path("retrato").asString("")
                    : "prestação de contas de " + resultado.path("ambito").asString("") + " " + resultado.path("retrato").asString("");
            case ResolverOcorrencia.CAPACIDADE -> verbo(resultado.path("acao").asString("")) + " a ocorrência "
                    + resultado.path("ocorrencia").path("parcela").asString("") + " de " + resultado.path("nome").asString("");
            case "desfazer" -> "desfez " + String.join(", ", revertidas(resultado));
            default -> o.capacidade();
        };
    }

    /** "foto n-..." ou "comprovante n-..."; sem natureza gravada, é comprovante. */
    private static String imagem(JsonNode resultado) {
        return ("foto".equals(resultado.path("natureza").asString(null)) ? "foto " : "comprovante ") + resultado.path("comprovante").asString("");
    }

    private static String genero(JsonNode resultado) {
        return "foto".equals(resultado.path("natureza").asString(null)) ? "a" : "o";
    }

    private static String texto(JsonNode argumentos, String entidade) {
        return (argumentos.path("ref").isString() ? "alteração de " : "criação de ") + entidade;
    }

    private static List<String> revertidas(JsonNode resultado) {
        List<String> ids = new ArrayList<>();
        resultado.path("operacoes").forEach(o -> ids.add(o.path("id").asString("")));
        return ids;
    }

    private static String primeiro(JsonNode a, JsonNode b) {
        return a.isString() && !a.asString().isBlank() ? a.asString() : b.asString("");
    }

    private static String verbo(String acao) {
        return switch (acao) {
            case "pular" -> "pulou";
            case "adiar" -> "adiou";
            default -> "vinculou";
        };
    }

    private static String dinheiro(JsonNode resultado) {
        return dinheiro(resultado.path("valor").asString("0"), resultado.path("moeda").asString("BRL"));
    }

    /** O valor como o dono lê: {@code R$ 1.234,56}, ou o código da moeda antes, fora do real. */
    static String dinheiro(String texto, String moeda) {
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        String valor = formato.format(new BigDecimal(texto));
        return ("BRL".equals(moeda) ? "R$ " : moeda + " ") + valor;
    }
}
