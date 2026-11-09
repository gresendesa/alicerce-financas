package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** Os tipos de meio de pagamento. */
public enum TipoMeioPagamento {
    PIX,
    DEBITO,
    CREDITO,
    DINHEIRO,
    TRANSFERENCIA,
    BOLETO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TipoMeioPagamento> deCodigo(String codigo) {
        for (TipoMeioPagamento tipo : values()) {
            if (tipo.codigo().equals(codigo)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
