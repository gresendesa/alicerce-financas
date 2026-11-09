package tools.alicerce.financeiro.aplicacao;

import java.util.ArrayList;
import java.util.List;

/** Os campos de moeda original de uma escrita, como chegam do assistente. */
public record EntradaMoeda(String valorOriginal, String moedaOriginal, String cotacao, String dataCotacao) {

    boolean informada() {
        return !(Argumentos.vazio(valorOriginal) && Argumentos.vazio(moedaOriginal) && Argumentos.vazio(cotacao)
                && Argumentos.vazio(dataCotacao));
    }

    /** O que falta para registrar. Sem moeda original, só {@code valor}; com ela, o valor pode vir pela cotação. */
    List<String> faltando(String valor) {
        List<String> campos = new ArrayList<>();
        if (!informada()) {
            if (Argumentos.vazio(valor)) {
                campos.add("valor");
            }
            return campos;
        }
        if (Argumentos.vazio(valorOriginal)) {
            campos.add("valor_original");
        }
        if (Argumentos.vazio(moedaOriginal)) {
            campos.add("moeda_original");
        }
        if (Argumentos.vazio(valor) && Argumentos.vazio(cotacao)) {
            campos.add("valor");
        }
        return campos;
    }

    Argumentos em(Argumentos argumentos) {
        return argumentos.com("valor_original", valorOriginal).com("moeda_original", moedaOriginal).com("cotacao", cotacao)
                .com("data_cotacao", dataCotacao);
    }
}
