package tools.alicerce.financeiro.aplicacao;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.alicerce.financeiro.dominio.CartaoCredito;
import tools.alicerce.financeiro.dominio.CompraNoCredito;
import tools.alicerce.financeiro.dominio.Fatura;
import tools.alicerce.financeiro.dominio.TipoLancamento;

/** Porta de persistência dos cartões e das faturas. Valores em centavos; só lançamentos vigentes contam. */
public interface RepositorioCartoes {

    /**
     * O que um lançamento vigente pôs numa fatura.
     *
     * @param centavos positivo aumenta a dívida; pagamentos entram positivos e ficam em {@code pagos}
     */
    record ItemDaFatura(UUID lancamento, String codigo, TipoLancamento tipo, LocalDate data, String descricao, String parcela,
            long centavos) {}

    /** @param comprado o que a fatura cobra; {@code pago} o que já foi pago */
    record Totais(long comprado, long pago) {}

    /** Um pagamento vigente de fatura. */
    record Pagamento(UUID lancamento, String codigo, String descricao, LocalDate data, long centavos, UUID fatura, UUID operacao,
            OffsetDateTime registradoEm) {}

    /** Uma parcela vigente que já estava dentro da abertura de uma fatura. @param parcela {@code 8/12}; ausente à vista */
    record ParcelaNaAbertura(UUID lancamento, String codigo, String descricao, String parcela, long centavos) {}

    List<CartaoCredito> cartoes(UUID dono);

    Optional<CartaoCredito> cartao(UUID conta);

    void inserir(OperacaoEmCurso operacao, CartaoCredito cartao);

    void atualizar(OperacaoEmCurso operacao, CartaoCredito cartao);

    Optional<Fatura> fatura(UUID cartao, String periodo);

    Optional<Fatura> faturaPorId(UUID dono, UUID id);

    /** As faturas do cartão, da que vence primeiro à última. */
    List<Fatura> faturas(UUID cartao);

    /** Grava a fatura. Falso se outra do mesmo cartão e período já existe. */
    boolean inserirFatura(OperacaoEmCurso operacao, Fatura fatura);

    /** Os totais das faturas que têm algum lançamento vigente. */
    Map<UUID, Totais> totais(UUID cartao);

    /** Os itens da fatura, sem os pagamentos. */
    List<ItemDaFatura> itens(UUID fatura);

    List<Pagamento> pagamentos(Collection<UUID> faturas);

    /** A fatura em que está o saldo de abertura vigente do cartão. */
    Optional<UUID> faturaDaAbertura(UUID cartao);

    List<ParcelaNaAbertura> parcelasNaAbertura(UUID fatura);

    /** As compras no crédito vigentes com alguma parcela vencendo no período, com todas as parcelas. */
    List<CompraNoCredito> comprasComVencimento(UUID dono, LocalDate de, LocalDate ate);
}
