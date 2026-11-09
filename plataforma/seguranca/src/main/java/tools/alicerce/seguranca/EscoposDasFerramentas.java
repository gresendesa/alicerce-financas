package tools.alicerce.seguranca;

import java.util.Optional;

/** Checagem 7: o escopo que cada tool exige. Implementado pelo adaptador MCP de cada módulo. */
public interface EscoposDasFerramentas {

    Optional<String> escopoDe(String ferramenta);
}
