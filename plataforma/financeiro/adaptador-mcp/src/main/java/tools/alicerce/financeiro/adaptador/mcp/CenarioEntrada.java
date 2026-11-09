package tools.alicerce.financeiro.adaptador.mcp;

import java.util.List;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.alicerce.financeiro.aplicacao.Projetar;

/** Um cenário em {@code projetar}. Campos opcionais no esquema: a falta volta como {@code campos_faltando}. */
record CenarioEntrada(
        @McpToolParam(description = "Nome curto do cenário, como \"10x no Nubank\".", required = false) String rotulo,
        @McpToolParam(description = "As hipóteses do cenário, aplicadas juntas; pelo menos uma.", required = false) List<HipoteseEntrada> hipoteses) {

    Projetar.Cenario cenario() {
        return new Projetar.Cenario(rotulo, hipoteses == null ? null : hipoteses.stream().map(h -> h == null ? null : h.hipotese()).toList());
    }
}
