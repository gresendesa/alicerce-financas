package tools.alicerce.financeiro.dominio;

import java.math.BigDecimal;
import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/** Uma linha de uma nota. */
public record ItemLancamento(String descricao, BigDecimal quantidade, UUID categoria, UUID ambito, Dinheiro valor) {}
