package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/**
 * Os tipos de declaração do perfil, como na modelagem. Uma declaração nova de um tipo substitui a anterior do
 * mesmo tipo.
 */
public enum TipoDeclaracao {
    /** Quanto da renda o dono quer poupar, em %. */
    INTENCAO_POUPANCA(true),
    /** O saldo que o dono quer ter sempre guardado: um valor ou meses de gasto. */
    RESERVA_MINIMA(true),
    /** O máximo da renda de um mês que as parcelas podem levar, em %. */
    TETO_PARCELAS(true),
    /** As metas que nenhum plano pode atrasar. */
    META_PROTEGIDA(true),
    /** Um texto livre do dono para o assistente; nenhum cálculo o lê. */
    NOTA(true),
    /** Saldo mínimo no fim de cada mês, com as contas do mês pagas. */
    PISO_PERIODO(true),
    /** Categorias que só entram num plano de cortes depois das outras. */
    CATEGORIAS_ESSENCIAIS(true);

    private final boolean aceito;

    TipoDeclaracao(boolean aceito) {
        this.aceito = aceito;
    }

    /** Já pode ser declarado. */
    public boolean aceito() {
        return aceito;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TipoDeclaracao> deCodigo(String codigo) {
        for (TipoDeclaracao tipo : values()) {
            if (tipo.codigo().equals(codigo)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
