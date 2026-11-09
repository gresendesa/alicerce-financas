package tools.alicerce.financeiro.dominio;

import java.util.List;
import java.util.UUID;

/** Para quem é o gasto (pessoal, família, igreja), independente da categoria. */
public record Ambito(UUID id, String nome, List<String> aliases, boolean padrao, boolean arquivado, long versao) {

    public Ambito {
        aliases = List.copyOf(aliases);
    }
}
