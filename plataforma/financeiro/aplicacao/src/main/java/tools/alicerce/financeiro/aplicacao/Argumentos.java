package tools.alicerce.financeiro.aplicacao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Os argumentos de uma escrita, como o registrador os grava e como o hash os compara. */
final class Argumentos {

    private final Map<String, Object> valores = new LinkedHashMap<>();

    Argumentos com(String nome, Object valor) {
        valores.put(nome, valor);
        return this;
    }

    Map<String, Object> mapa() {
        return valores;
    }

    /** Listas vazias e nulas pedem a mesma coisa. */
    static List<Object> lista(List<?> valores) {
        return valores == null ? null : new ArrayList<>(valores);
    }

    static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }
}
