package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/**
 * Converte saldos para a moeda do dono, sem somar moedas diferentes: cada saldo estrangeiro passa pela
 * cotação da sua moeda e, sem cotação, fica fora do total. Só converte: ganho ou perda cambial não é
 * calculado. O arredondamento é {@link RoundingMode#HALF_EVEN} na escala 2, como em {@link ValorOriginal}.
 */
public final class CalculoConversao {

    private CalculoConversao() {}

    public record SaldoConta(UUID conta, Dinheiro saldo) {}

    /** @param lancamento o id curto do lançamento em que o dono registrou esta cotação */
    public record Cotacao(BigDecimal valor, LocalDate data, String lancamento) {}

    /** @param convertido {@code null} se não há cotação */
    public record Linha(UUID conta, Dinheiro saldo, Cotacao cotacao, Dinheiro convertido) {}

    /**
     * @param naMoedaDoDono a soma das contas que já estão na moeda do dono
     * @param convertido a soma das contas estrangeiras convertidas
     * @param total as duas somas juntas
     * @param linhas as contas estrangeiras, convertidas ou não
     * @param foraDoTotal as contas com saldo e sem cotação
     */
    public record Resultado(Dinheiro naMoedaDoDono, Dinheiro convertido, Dinheiro total, List<Linha> linhas, List<Linha> foraDoTotal) {}

    public static Resultado converter(List<SaldoConta> saldos, Map<Currency, Cotacao> cotacoes, Currency moedaDoDono) {
        Dinheiro naMoedaDoDono = Dinheiro.zero(moedaDoDono);
        Dinheiro convertido = Dinheiro.zero(moedaDoDono);
        List<Linha> linhas = new ArrayList<>();
        List<Linha> foraDoTotal = new ArrayList<>();
        for (SaldoConta s : saldos) {
            Currency moeda = s.saldo().moeda();
            if (moeda.equals(moedaDoDono)) {
                naMoedaDoDono = naMoedaDoDono.somar(s.saldo());
                continue;
            }
            Cotacao cotacao = cotacoes.get(moeda);
            Linha linha = new Linha(s.conta(), s.saldo(), cotacao, cotacao == null ? null : emMoedaDoDono(s.saldo(), cotacao, moedaDoDono));
            linhas.add(linha);
            if (linha.convertido() != null) {
                convertido = convertido.somar(linha.convertido());
            } else if (!s.saldo().ehZero()) {
                foraDoTotal.add(linha);
            }
        }
        return new Resultado(naMoedaDoDono, convertido, naMoedaDoDono.somar(convertido), List.copyOf(linhas), List.copyOf(foraDoTotal));
    }

    private static Dinheiro emMoedaDoDono(Dinheiro saldo, Cotacao cotacao, Currency moedaDoDono) {
        return new Dinheiro(saldo.valor().multiply(cotacao.valor(), MathContext.UNLIMITED).setScale(Dinheiro.ESCALA, RoundingMode.HALF_EVEN), moedaDoDono);
    }
}
