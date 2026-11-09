package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import tools.alicerce.convencoes.ErroTipado;

/**
 * O que uma conta de cartão tem a mais. Compra até o dia de fechamento, inclusive, entra na fatura que
 * fecha ali; dia maior que o mês cai no último dia. O vencimento é no mesmo mês do fechamento quando o
 * dia dele vem depois; senão, no mês seguinte. A regra olha os dias configurados, e não as datas, para
 * dois ciclos nunca caírem no mesmo mês de vencimento em fevereiro.
 *
 * @param conta a conta do cartão
 * @param limiteCentavos nulo se não foi informado
 * @param contaPagamento a conta de onde a fatura costuma sair; nula se não foi informada
 */
public record CartaoCredito(UUID conta, int diaFechamento, int diaVencimento, Long limiteCentavos, UUID contaPagamento,
        long versao) {

    public CartaoCredito {
        exigirDia(diaFechamento, "cartao.dia_fechamento");
        exigirDia(diaVencimento, "cartao.dia_vencimento");
        if (limiteCentavos != null && limiteCentavos < 0) {
            throw ErroTipado.validacao("cartao.limite", "O limite não pode ser negativo.");
        }
    }

    private static void exigirDia(int dia, String campo) {
        if (dia < 1 || dia > 31) {
            throw ErroTipado.validacao(campo, "O dia vai de 1 a 31.");
        }
    }

    /** O ciclo em que uma compra feita nesta data entra. */
    public CicloFatura cicloDaCompra(LocalDate data) {
        YearMonth mes = YearMonth.from(data);
        return ciclo(data.isAfter(noMes(mes, diaFechamento)) ? mes.plusMonths(1) : mes);
    }

    /**
     * O ciclo da compra como a escrita o escolhe: depois de mudar os dias do cartão, uma fatura já criada pode ter
     * fechado antes da data, e a compra vai para a seguinte.
     *
     * @param existente a fatura já gravada de um período, se há
     */
    public CicloFatura cicloDaCompra(LocalDate data, Function<String, Optional<Fatura>> existente) {
        CicloFatura ciclo = cicloDaCompra(data);
        Optional<Fatura> fatura = existente.apply(ciclo.periodo());
        while (fatura.isPresent() && fatura.get().fechaEm().isBefore(data)) {
            ciclo = seguinte(ciclo);
            fatura = existente.apply(ciclo.periodo());
        }
        return ciclo;
    }

    /**
     * Os ciclos de uma compra em {@code parcelas}, um por fatura, a partir do ciclo da compra: os mesmos que
     * {@code registrar_gasto} usa para gravar cada parcela.
     */
    public List<CicloFatura> ciclosDaCompra(LocalDate data, int parcelas, Function<String, Optional<Fatura>> existente) {
        List<CicloFatura> ciclos = new ArrayList<>();
        CicloFatura ciclo = cicloDaCompra(data, existente);
        for (int i = 0; i < parcelas; i++) {
            ciclos.add(ciclo);
            ciclo = seguinte(ciclo);
        }
        return ciclos;
    }

    /** Quando vence a fatura de um ciclo: a da fatura já gravada, se há, senão a dos dias do cartão. */
    public static LocalDate vencimento(CicloFatura ciclo, Function<String, Optional<Fatura>> existente) {
        return existente.apply(ciclo.periodo()).map(Fatura::venceEm).orElse(ciclo.venceEm());
    }

    public CicloFatura seguinte(CicloFatura ciclo) {
        return ciclo(ciclo.mesFechamento().plusMonths(1));
    }

    /** O primeiro ciclo que vence nesta data ou depois, mesmo que já tenha fechado. */
    public CicloFatura proximoAVencer(LocalDate data) {
        CicloFatura anterior = ciclo(cicloDaCompra(data).mesFechamento().minusMonths(1));
        return anterior.venceEm().isBefore(data) ? seguinte(anterior) : anterior;
    }

    /**
     * O número da parcela que está na próxima fatura a vencer em {@code dia}, contando pela data da compra.
     * Pode passar do total: a compra já terminou.
     */
    public int parcelaEm(LocalDate compra, LocalDate dia) {
        long meses = ChronoUnit.MONTHS.between(cicloDaCompra(compra).mesFechamento(), proximoAVencer(dia).mesFechamento());
        return Math.toIntExact(meses + 1);
    }

    private CicloFatura ciclo(YearMonth mesFechamento) {
        YearMonth mesVencimento = diaVencimento > diaFechamento ? mesFechamento : mesFechamento.plusMonths(1);
        return new CicloFatura(mesFechamento, noMes(mesFechamento, diaFechamento), noMes(mesVencimento, diaVencimento));
    }

    private static LocalDate noMes(YearMonth mes, int dia) {
        return mes.atDay(Math.min(dia, mes.lengthOfMonth()));
    }
}
