package tools.alicerce.convencoes;

import java.util.Objects;

/** A chave que o assistente gera por intenção do dono e reutiliza ao repetir a chamada. */
public record ChaveIdempotencia(String valor) {

    public static final int TAMANHO_MAXIMO = 64;
    public static final String CAMPO = "chave_idempotencia";

    public ChaveIdempotencia {
        Objects.requireNonNull(valor, "valor");
    }

    public static ChaveIdempotencia ler(String texto) {
        if (texto == null || texto.isBlank()) {
            throw ErroTipado.camposFaltando(java.util.List.of(CAMPO), null);
        }
        if (texto.length() > TAMANHO_MAXIMO || !texto.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
            throw ErroTipado.validacao(CAMPO,
                    "Chave de idempotência inválida: até 64 caracteres ASCII visíveis, sem espaços.");
        }
        return new ChaveIdempotencia(texto);
    }
}
