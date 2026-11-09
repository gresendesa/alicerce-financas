package tools.alicerce.financeiro.dominio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;
import tools.alicerce.convencoes.ErroTipado;

/** Monta os movimentos de cada tipo de lançamento. Toda lista devolvida soma zero. */
public final class Partidas {

    private Partidas() {}

    private record Chave(UUID categoria, UUID ambito) {}

    /**
     * Um gasto: partidas iguais em categoria e âmbito viram um movimento só, e a conta de liquidação
     * tem uma saída por âmbito, para todo movimento ter âmbito.
     */
    public static List<Movimento> gasto(Dinheiro total, UUID liquidacao, List<Partida> partidas) {
        Dinheiro zero = Dinheiro.zero(total.moeda());
        Map<Chave, Dinheiro> porCategoria = new LinkedHashMap<>();
        Map<UUID, Dinheiro> porAmbito = new LinkedHashMap<>();
        for (Partida partida : partidas) {
            porCategoria.merge(new Chave(partida.categoria(), partida.ambito()), partida.valor(), Dinheiro::somar);
            porAmbito.merge(partida.ambito(), partida.valor(), Dinheiro::somar);
        }
        Dinheiro soma = porAmbito.values().stream().reduce(zero, Dinheiro::somar);
        if (soma.compareTo(total) != 0) {
            throw ErroTipado.validacao("itens",
                    "A soma dos itens (" + soma.texto() + ") é diferente do total (" + total.texto() + ").");
        }
        List<Movimento> movimentos = new ArrayList<>();
        porCategoria.forEach((chave, valor) -> movimentos.add(new Movimento(chave.categoria(), valor, chave.ambito())));
        porAmbito.forEach((ambito, valor) -> movimentos.add(new Movimento(liquidacao, valor.negar(), ambito)));
        return List.copyOf(movimentos);
    }

    /**
     * Uma compra no crédito, uma parcela por fatura. A categoria recebe o gasto inteiro; o cartão se divide
     * em parcelas e, dentro de cada uma, por âmbito. As partes sempre fecham com o total, ao centavo.
     *
     * @param faturas uma por parcela, em ordem
     */
    public static List<Movimento> gastoNoCredito(Dinheiro total, UUID cartao, List<Partida> partidas, List<UUID> faturas) {
        return gastoEmParcelas(total, cartao, partidas, faturas.stream().map(f -> DestinoParcela.noCartao(cartao, f)).toList());
    }

    /**
     * Uma compra com cada parcela no seu destino, com a numeração real. A categoria recebe sempre o gasto
     * inteiro; numa compra em andamento, o que não passa pelo cartão sai de Saldo de abertura.
     *
     * @param destinos um por parcela, em ordem
     */
    public static List<Movimento> gastoEmParcelas(Dinheiro total, UUID cartao, List<Partida> partidas, List<DestinoParcela> destinos) {
        List<Movimento> inteiro = gasto(total, cartao, partidas);
        List<Movimento> movimentos = new ArrayList<>(inteiro.stream().filter(m -> !m.conta().equals(cartao)).toList());
        List<Movimento> saidas = inteiro.stream().filter(m -> m.conta().equals(cartao)).toList();
        int quantidade = destinos.size();
        long[] porParcela = Repartir.parcelas(total.centavos(), quantidade);
        long[] porAmbito = saidas.stream().mapToLong(m -> -m.valor().centavos()).toArray();
        long[][] partes = Repartir.matriz(porParcela, porAmbito);
        Integer totalParcelas = quantidade == 1 ? null : Integer.valueOf(quantidade);
        for (int p = 0; p < quantidade; p++) {
            Integer parcela = quantidade == 1 ? null : Integer.valueOf(p + 1);
            DestinoParcela destino = destinos.get(p);
            for (int a = 0; a < saidas.size(); a++) {
                if (partes[p][a] > 0) {
                    movimentos.add(new Movimento(destino.conta(), Dinheiro.deCentavos(-partes[p][a], total.moeda()), saidas.get(a).ambito(),
                            destino.fatura(), parcela, totalParcelas, destino.faturaAbertura()));
                }
            }
        }
        return List.copyOf(movimentos);
    }

    /** Dinheiro entre contas do próprio dono: sem âmbito, porque não é gasto nem ganho. */
    public static List<Movimento> transferencia(Dinheiro valor, UUID de, UUID para) {
        return List.of(new Movimento(de, valor.negar(), null), new Movimento(para, valor, null));
    }

    /**
     * Câmbio entre contas: o valor passa pela conversão de cada moeda, e cada moeda soma zero por conta própria.
     *
     * @param conversaoOrigem a conta de conversão na moeda de {@code naOrigem}
     * @param conversaoDestino a conta de conversão na moeda de {@code noDestino}
     */
    public static List<Movimento> cambio(Dinheiro naOrigem, UUID origem, UUID conversaoOrigem, Dinheiro noDestino, UUID conversaoDestino, UUID destino) {
        return List.of(new Movimento(origem, naOrigem.negar(), null), new Movimento(conversaoOrigem, naOrigem, null),
                new Movimento(conversaoDestino, noDestino.negar(), null), new Movimento(destino, noDestino, null));
    }

    /** Os mesmos movimentos de um câmbio com outros valores, mantendo o lado e a moeda de cada um. */
    public static List<Movimento> comCambio(List<Movimento> movimentos, Dinheiro umValor, Dinheiro outroValor) {
        return movimentos.stream().map(m -> {
            Dinheiro novo = m.valor().moeda().equals(umValor.moeda()) ? umValor : outroValor;
            return m.comValor(m.valor().ehNegativo() ? novo.negar() : novo);
        }).toList();
    }

    /** Pagar a fatura é uma transferência da conta para o cartão, marcada com a fatura paga. */
    public static List<Movimento> pagamentoFatura(Dinheiro valor, UUID de, UUID cartao, UUID fatura) {
        return List.of(new Movimento(de, valor.negar(), null), new Movimento(cartao, valor, null, fatura, null, null));
    }

    /** Os mesmos dois movimentos com outro valor, mantendo o lado de cada um. */
    public static List<Movimento> comValor(List<Movimento> movimentos, Dinheiro valor) {
        if (movimentos.size() != 2) {
            throw new IllegalArgumentException("Só um lançamento de dois movimentos muda de valor assim");
        }
        return movimentos.stream()
                .map(m -> m.comValor(m.valor().ehNegativo() ? valor.negar() : valor)).toList();
    }

    /** Um ganho: o valor entra na conta e sai da fonte de renda. */
    public static List<Movimento> ganho(Dinheiro total, UUID conta, UUID fonte, UUID ambito) {
        return List.of(new Movimento(conta, total, ambito), new Movimento(fonte, total.negar(), ambito));
    }

    /** O saldo de uma conta nova, contra a conta técnica. Saldo devedor de passivo entra negativo. */
    public static List<Movimento> saldoAbertura(Dinheiro saldo, Conta conta, UUID contaTecnica) {
        Dinheiro naConta = conta.classe() == ClasseConta.PASSIVO ? saldo.negar() : saldo;
        return List.of(new Movimento(conta.id(), naConta, null), new Movimento(contaTecnica, naConta.negar(), null));
    }
}
