package tools.alicerce.financeiro.dominio;

import java.util.List;
import java.util.UUID;

/** Como um pagamento é feito e de que conta o dinheiro sai. */
public record MeioPagamento(UUID id, TipoMeioPagamento tipo, String nome, List<String> aliases, UUID contaLiquidacao,
        boolean padrao, UUID ambitoPadrao, boolean arquivado, long versao) {

    public MeioPagamento {
        aliases = List.copyOf(aliases);
    }
}
