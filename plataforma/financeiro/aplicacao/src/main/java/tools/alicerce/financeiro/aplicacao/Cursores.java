package tools.alicerce.financeiro.aplicacao;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import tools.alicerce.convencoes.ErroTipado;

/** Cursores de paginação: texto opaco para o assistente, que só o devolve. */
final class Cursores {

    private Cursores() {}

    static String escrever(String... partes) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join("|", partes).getBytes(StandardCharsets.UTF_8));
    }

    static List<String> ler(String cursor, int partes) {
        try {
            List<String> lidas = List.of(new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8).split("\\|"));
            if (lidas.size() == partes) {
                return lidas;
            }
        } catch (IllegalArgumentException invalido) {
            // Cai no erro abaixo.
        }
        throw invalido();
    }

    static ErroTipado invalido() {
        return ErroTipado.validacao("cursor", "Cursor inválido: use o proximo_cursor devolvido pela página anterior.");
    }
}
