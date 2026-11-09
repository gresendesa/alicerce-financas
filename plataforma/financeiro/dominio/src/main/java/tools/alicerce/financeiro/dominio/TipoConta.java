package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** Os tipos de conta, cada um na sua classe. Os de patrimônio são contrapartidas técnicas, nunca pedidos. */
public enum TipoConta {
    CORRENTE(ClasseConta.ATIVO),
    POUPANCA(ClasseConta.ATIVO),
    CARTEIRA(ClasseConta.ATIVO),
    INVESTIMENTO(ClasseConta.ATIVO),
    RESERVA(ClasseConta.ATIVO),
    CARTAO_CREDITO(ClasseConta.PASSIVO),
    EMPRESTIMO(ClasseConta.PASSIVO),
    CATEGORIA(ClasseConta.DESPESA),
    FONTE_RENDA(ClasseConta.RECEITA),
    SALDO_ABERTURA(ClasseConta.PATRIMONIO),
    NAO_IDENTIFICADO(ClasseConta.PATRIMONIO),
    /** Por onde um câmbio passa, uma por moeda: assim cada moeda soma zero por conta própria. */
    CONVERSAO(ClasseConta.PATRIMONIO);

    private final ClasseConta classe;

    TipoConta(ClasseConta classe) {
        this.classe = classe;
    }

    public ClasseConta classe() {
        return classe;
    }

    /** Conta onde o dinheiro fica: tem saldo, e não é categoria nem contrapartida técnica. */
    public boolean temSaldo() {
        return classe == ClasseConta.ATIVO || classe == ClasseConta.PASSIVO;
    }

    /** O lado do gasto ou do ganho: categoria ou fonte de renda, nunca dinheiro nem contrapartida técnica. */
    public boolean ehResultado() {
        return classe == ClasseConta.DESPESA || classe == ClasseConta.RECEITA;
    }

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<TipoConta> deCodigo(String codigo) {
        for (TipoConta tipo : values()) {
            if (tipo.codigo().equals(codigo)) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }
}
