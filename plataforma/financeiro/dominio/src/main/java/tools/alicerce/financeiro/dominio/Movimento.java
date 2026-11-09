package tools.alicerce.financeiro.dominio;

import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/**
 * Uma perna de um lançamento: positivo entra na conta, negativo sai.
 *
 * @param fatura a fatura, só em movimento de cartão
 * @param parcela o número da parcela; nulo em compra à vista
 * @param totalParcelas em quantas parcelas a compra foi feita; nulo em compra à vista
 * @param faturaAbertura a fatura cuja abertura já contém esta parcela; só em Saldo de abertura
 */
public record Movimento(UUID conta, Dinheiro valor, UUID ambito, UUID fatura, Integer parcela, Integer totalParcelas,
        UUID faturaAbertura) {

    public Movimento(UUID conta, Dinheiro valor, UUID ambito) {
        this(conta, valor, ambito, null, null, null, null);
    }

    public Movimento(UUID conta, Dinheiro valor, UUID ambito, UUID fatura, Integer parcela, Integer totalParcelas) {
        this(conta, valor, ambito, fatura, parcela, totalParcelas, null);
    }

    public Movimento invertido() {
        return comValor(valor.negar());
    }

    public Movimento comValor(Dinheiro novo) {
        return new Movimento(conta, novo, ambito, fatura, parcela, totalParcelas, faturaAbertura);
    }

    public Movimento comAmbito(UUID novo) {
        return new Movimento(conta, valor, novo, fatura, parcela, totalParcelas, faturaAbertura);
    }

    public Movimento naFatura(UUID nova) {
        return new Movimento(conta, valor, ambito, nova, parcela, totalParcelas, faturaAbertura);
    }

    /** {@code 2/3}; nulo em compra à vista. */
    public String parcelaTexto() {
        return parcela == null ? null : parcela + "/" + totalParcelas;
    }
}
