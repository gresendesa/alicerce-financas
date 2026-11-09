package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Algo combinado para vencer no futuro: um pagamento ou um recebimento, recorrente, parcelado ou único. Não
 * gera lançamento sozinho: o pagamento é um gasto ou ganho vinculado a uma ocorrência. As datas das
 * ocorrências saem daqui, e só a ocorrência paga, pulada ou adiada é gravada.
 *
 * @param referencia número do parcelamento ou protocolo, para o dono achar no banco ou no órgão; nunca senha
 * @param valorCentavos o valor de cada ocorrência; em parcelado com total, o da parcela comum
 * @param valorTotalCentavos só em parcelado informado pelo total: a sobra de centavos vai na primeira parcela
 * @param parcelas quantas parcelas, só em parcelado
 * @param parcelasPagasAntes as primeiras parcelas, pagas antes de o compromisso entrar no sistema
 * @param fim a última data em que uma ocorrência pode vencer; nula se não há
 * @param categoria a categoria ou fonte de renda sugerida para o pagamento; nula se não há
 * @param conta a conta de onde sai ou onde entra o dinheiro; nula se não há
 * @param encerrado o dono encerrou o compromisso: nada vence depois de {@code fim}
 */
public record Compromisso(UUID id, DirecaoCompromisso direcao, TipoCompromisso tipo, String nome, List<String> aliases,
        String contraparte, String referencia, long valorCentavos, Long valorTotalCentavos, String moeda,
        FrequenciaCompromisso frequencia, LocalDate primeiroVencimento, Integer parcelas, int parcelasPagasAntes, LocalDate fim,
        UUID categoria, UUID conta, UUID meioPagamento, UUID ambito, String observacao, boolean encerrado, long versao) {

    /** Teto de parcelas: parcelamentos de tributos passam de 48, mas não de 40 anos. */
    public static final int MAXIMO_PARCELAS = 480;

    public Compromisso {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direcao, "direcao");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(frequencia, "frequencia");
        Objects.requireNonNull(primeiroVencimento, "primeiroVencimento");
        aliases = List.copyOf(aliases);
        if ((tipo == TipoCompromisso.PARCELADO) != (parcelas != null)) {
            throw new IllegalArgumentException("Só compromisso parcelado tem parcelas");
        }
        if (valorCentavos <= 0) {
            throw new IllegalArgumentException("Valor de compromisso tem de ser positivo");
        }
    }

    /** Tem fim conhecido: dá para dizer quanto falta. */
    public boolean finito() {
        return tipo != TipoCompromisso.RECORRENTE || fim != null;
    }

    /** O vencimento da ocorrência {@code numero}, a partir de 1. Dia maior que o mês cai no último dia. */
    public LocalDate vencimento(int numero) {
        if (numero < 1) {
            throw new IllegalArgumentException("Ocorrência começa em 1");
        }
        YearMonth base = YearMonth.from(primeiroVencimento);
        YearMonth mes = frequencia == FrequenciaCompromisso.MENSAL ? base.plusMonths(numero - 1L) : base.plusYears(numero - 1L);
        return mes.atDay(Math.min(primeiroVencimento.getDayOfMonth(), mes.lengthOfMonth()));
    }

    /** A ocorrência existe pela regra: dentro do número de parcelas e até o fim. */
    public boolean existe(int numero) {
        if (numero < 1) {
            return false;
        }
        int limite = switch (tipo) {
            case UNICO -> 1;
            case PARCELADO -> parcelas;
            case RECORRENTE -> Integer.MAX_VALUE;
        };
        return numero <= limite && (fim == null || !vencimento(numero).isAfter(fim));
    }

    /** O valor previsto da ocorrência: em parcelado pelo total, a primeira leva a sobra de centavos. */
    public long valorCentavos(int numero) {
        if (valorTotalCentavos == null) {
            return valorCentavos;
        }
        long comum = valorTotalCentavos / parcelas;
        return numero == 1 ? comum + valorTotalCentavos % parcelas : comum;
    }

    /** O número da ocorrência que vence num mês, pela regra; 0 se nenhuma. */
    public int numeroNoMes(YearMonth mes) {
        YearMonth base = YearMonth.from(primeiroVencimento);
        long distancia = frequencia == FrequenciaCompromisso.MENSAL ? base.until(mes, java.time.temporal.ChronoUnit.MONTHS)
                : base.getMonth() == mes.getMonth() ? base.until(mes, java.time.temporal.ChronoUnit.YEARS) : -1;
        if (distancia < 0 || distancia >= Integer.MAX_VALUE) {
            return 0;
        }
        int numero = (int) distancia + 1;
        return existe(numero) ? numero : 0;
    }
}
