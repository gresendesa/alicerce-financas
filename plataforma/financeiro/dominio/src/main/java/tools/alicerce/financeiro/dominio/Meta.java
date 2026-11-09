package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma meta de valor: juntar um valor, de preferência até uma data. Só a intenção fica aqui; o progresso é a soma
 * dos aportes menos os resgates registrados, e nunca é guardado.
 *
 * @param alvoCentavos quanto o dono quer juntar, na moeda da meta
 * @param dataAlvo até quando; nula se o dono não marcou data
 * @param prioridade de 1 (a mais importante) a 5
 * @param contaReservada a conta separada para a meta (reserva ou poupança); nula se não há. Fica fora do saldo disponível
 * @param arquivada desfeita a criação: some das listas, e o histórico fica
 */
public record Meta(UUID id, String nome, List<String> aliases, long alvoCentavos, String moeda, LocalDate dataAlvo, int prioridade,
        UUID contaReservada, SituacaoMeta situacao, String observacao, boolean arquivada, long versao) {

    public static final int PRIORIDADE_PADRAO = 3;
    public static final int PRIORIDADE_MINIMA = 1;
    public static final int PRIORIDADE_MAXIMA = 5;

    public Meta {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nome, "nome");
        Objects.requireNonNull(moeda, "moeda");
        Objects.requireNonNull(situacao, "situacao");
        aliases = List.copyOf(aliases);
        if (alvoCentavos <= 0) {
            throw new IllegalArgumentException("Valor alvo tem de ser positivo");
        }
        if (prioridade < PRIORIDADE_MINIMA || prioridade > PRIORIDADE_MAXIMA) {
            throw new IllegalArgumentException("Prioridade fora de 1 a 5");
        }
    }

    /** Vale para as contas de hoje: ativa e não arquivada. */
    public boolean emAndamento() {
        return situacao == SituacaoMeta.ATIVA && !arquivada;
    }
}
