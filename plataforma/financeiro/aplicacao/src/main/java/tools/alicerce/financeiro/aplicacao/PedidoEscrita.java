package tools.alicerce.financeiro.aplicacao;

import java.util.Map;
import java.util.Objects;
import tools.alicerce.convencoes.ChaveIdempotencia;

/**
 * Uma chamada de escrita, como o registrador a grava.
 *
 * @param capacidade o nome da capacidade, como {@code registrar_gasto}
 * @param argumentos os argumentos já normalizados, sem a chave de idempotência
 * @param chave a chave de idempotência; sem ela só a prévia do {@code desfazer} e o que não se repete, como
 *     gerar um link de envio ou consumir um token de uso único
 * @param lote o lote escolhido pelo assistente, ou {@code null}
 */
public record PedidoEscrita(String capacidade, Map<String, ?> argumentos, ChaveIdempotencia chave, String lote) {

    public PedidoEscrita {
        Objects.requireNonNull(capacidade, "capacidade");
        Objects.requireNonNull(argumentos, "argumentos");
    }
}
