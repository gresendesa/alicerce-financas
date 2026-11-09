package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** As ocorrências de um compromisso e quanto falta, a partir da regra e do que foi gravado. Só calcula. */
public final class CalculoCompromisso {

    /**
     * Quanto já foi e quanto falta. Os valores restantes são nulos num recorrente sem fim.
     *
     * @param valorPagoCentavos a soma de todos os pagamentos que valem, inclusive os parciais e os de ocorrência pulada depois
     * @param valorRestanteCentavos o que falta das ocorrências em aberto, já descontado o que foi pago em parte
     * @param valorAtrasadoCentavos o que falta das ocorrências atrasadas
     * @param pagasEmParte quantas das ocorrências em aberto já têm parte paga
     */
    public record Saldo(int pagas, int puladas, int emAberto, int atrasadas, long valorPagoCentavos, Long valorRestanteCentavos,
            Long valorAtrasadoCentavos, Integer total, Optional<Ocorrencia> proxima, int pagasEmParte) {}

    private CalculoCompromisso() {}

    /**
     * As ocorrências até {@code ate}, inclusive, pela data que vale, mais as gravadas depois dela. Num
     * compromisso finito, {@code ate} nulo traz todas. {@code gravacoes} traz, de cada número, as gravações
     * que valem na ordem em que foram feitas.
     */
    public static List<Ocorrencia> ocorrencias(Compromisso c, Map<Integer, List<RegistroOcorrencia>> gravacoes, LocalDate hoje, LocalDate ate) {
        if (ate == null && !c.finito()) {
            throw new IllegalArgumentException("Recorrente sem fim precisa de um limite");
        }
        List<Ocorrencia> lista = new ArrayList<>();
        int numero = 1;
        for (; c.existe(numero); numero++) {
            if (ate != null && c.vencimento(numero).isAfter(ate)) {
                break;
            }
            lista.add(ocorrencia(c, numero, gravacoes.get(numero), hoje));
        }
        int ultimo = numero - 1;
        // Gravadas depois do limite: paga adiantada, ou paga antes de um encerramento que a tirou da regra.
        gravacoes.forEach((n, doNumero) -> {
            boolean paga = doNumero.stream().anyMatch(r -> r.situacao() == RegistroOcorrencia.Situacao.PAGA);
            if (n > ultimo && !doNumero.isEmpty() && (c.existe(n) || paga)) {
                lista.add(ocorrencia(c, n, doNumero, hoje));
            }
        });
        lista.sort(Comparator.comparingInt(Ocorrencia::numero));
        return lista;
    }

    /**
     * A ocorrência {@code numero}, pela regra e pelas gravações dela, em ordem. Os pagamentos somam: a
     * ocorrência fica paga quando a soma chega ao previsto e, antes disso, em aberto pelo que falta, atrasada
     * depois do vencimento. Pular encerra o que falta; um pagamento ou um
     * adiamento depois do pulo a reabre, como a última gravação valia antes. {@code gravacoes} pode ser nulo.
     */
    public static Ocorrencia ocorrencia(Compromisso c, int numero, List<RegistroOcorrencia> gravacoes, LocalDate hoje) {
        LocalDate original = c.vencimento(numero);
        long previsto = c.valorCentavos(numero);
        List<RegistroOcorrencia> lista = gravacoes == null ? List.of() : gravacoes;
        if (lista.isEmpty() && numero <= c.parcelasPagasAntes()) {
            return new Ocorrencia(numero, original, original, previsto, SituacaoOcorrencia.PAGA_ANTES, false, null, null, null, null, null,
                    null, List.of());
        }
        List<Ocorrencia.PagamentoOcorrencia> pagamentos = new ArrayList<>();
        boolean pulada = false;
        LocalDate novaData = null;
        String observacao = null;
        for (RegistroOcorrencia r : lista) {
            switch (r.situacao()) {
                case PAGA -> {
                    pagamentos.add(new Ocorrencia.PagamentoOcorrencia(r.lancamento(),
                            r.valorPagoCentavos() == null ? 0 : r.valorPagoCentavos(), r.dataPagamento()));
                    pulada = false;
                }
                case PULADA -> pulada = true;
                case ADIADA -> {
                    novaData = r.novaData();
                    pulada = false;
                }
            }
            if (r.observacao() != null) {
                observacao = r.observacao();
            }
        }
        Ocorrencia.PagamentoOcorrencia ultimo = pagamentos.isEmpty() ? null : pagamentos.get(pagamentos.size() - 1);
        Long pago = ultimo == null ? null : pagamentos.stream().mapToLong(Ocorrencia.PagamentoOcorrencia::valorCentavos).sum();
        UUID lancamento = ultimo == null ? null : ultimo.lancamento();
        LocalDate dataPagamento = ultimo == null ? null : ultimo.data();
        if (pulada) {
            return new Ocorrencia(numero, original, original, previsto, SituacaoOcorrencia.PULADA, false, lancamento, pago, dataPagamento,
                    null, observacao, null, pagamentos);
        }
        if (pago != null && pago >= previsto) {
            return new Ocorrencia(numero, original, original, previsto, SituacaoOcorrencia.PAGA, false, lancamento, pago, dataPagamento,
                    pago - previsto, observacao, null, pagamentos);
        }
        LocalDate vale = novaData == null ? original : novaData;
        SituacaoOcorrencia situacao = vale.isBefore(hoje) ? SituacaoOcorrencia.ATRASADA : SituacaoOcorrencia.PREVISTA;
        long restante = previsto - (pago == null ? 0 : pago);
        return new Ocorrencia(numero, original, vale, previsto, situacao, novaData != null, lancamento, pago, dataPagamento, null,
                observacao, restante, pagamentos);
    }

    /**
     * O que já foi e o que falta. Num compromisso finito, {@code todas} tem de ser a lista inteira; num
     * recorrente sem fim, só conta até onde a lista vai, e os valores restantes ficam nulos.
     */
    public static Saldo saldo(Compromisso c, List<Ocorrencia> todas) {
        int pagas = 0;
        int puladas = 0;
        int abertas = 0;
        int atrasadas = 0;
        int emParte = 0;
        long pago = 0;
        long restante = 0;
        long atrasado = 0;
        Ocorrencia proxima = null;
        for (Ocorrencia o : todas) {
            if (o.valorPagoCentavos() != null) {
                pago += o.valorPagoCentavos();
            }
            switch (o.situacao()) {
                case PAGA, PAGA_ANTES -> pagas++;
                case PULADA -> puladas++;
                case ATRASADA, PREVISTA -> {
                    abertas++;
                    restante += o.valorRestanteCentavos();
                    if (o.pagaEmParte()) {
                        emParte++;
                    }
                    if (o.situacao() == SituacaoOcorrencia.ATRASADA) {
                        atrasadas++;
                        atrasado += o.valorRestanteCentavos();
                    }
                    if (proxima == null || o.vencimento().isBefore(proxima.vencimento())) {
                        proxima = o;
                    }
                }
            }
        }
        boolean finito = c.finito();
        return new Saldo(pagas, puladas, abertas, atrasadas, pago, finito ? restante : null, atrasado,
                finito ? todas.size() : null, Optional.ofNullable(proxima), emParte);
    }
}
