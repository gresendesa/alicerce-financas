package tools.alicerce.financeiro.dominio;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma fatura de um cartão. Não muda depois de criada; a situação sai de {@link SituacaoFatura}.
 *
 * @param periodo o mês do vencimento, como {@code 2026-11}
 */
public record Fatura(UUID id, UUID cartao, String periodo, LocalDate fechaEm, LocalDate venceEm) {}
