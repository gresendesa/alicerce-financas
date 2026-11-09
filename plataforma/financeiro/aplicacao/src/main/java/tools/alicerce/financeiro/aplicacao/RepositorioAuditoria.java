package tools.alicerce.financeiro.aplicacao;

import java.util.Set;
import java.util.UUID;
import tools.alicerce.convencoes.CodigoErro;

/** Porta de persistência dos eventos de auditoria: quem chamou o quê, e como terminou. */
public interface RepositorioAuditoria {

    /**
     * Uma chamada de capacidade.
     *
     * @param codigoErro {@code null} quando a chamada deu certo
     * @param operacao a operação gerada ou repetida, ou {@code null}
     */
    record EventoAuditoria(UUID dono, String cliente, Set<String> escopos, String capacidade,
            String chaveIdempotencia, String argumentosJson, CodigoErro codigoErro, UUID operacao) {}

    void registrar(EventoAuditoria evento);
}
