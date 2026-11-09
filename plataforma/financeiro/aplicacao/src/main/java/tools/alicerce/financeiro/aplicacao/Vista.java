package tools.alicerce.financeiro.aplicacao;

import java.util.List;
import java.util.Map;

/**
 * Uma tela descrita para o renderizador, sempre já validada: os componentes dizem como
 * mostrar, e {@code dados} traz o que mostrar, vindo só das capacidades.
 *
 * @param fontes de onde saíram os dados de uma vista composta pelo assistente; ausente nas telas padrão
 */
public record Vista(String catalogo, String raiz, List<Map<String, Object>> componentes, Map<String, Object> dados,
        List<Map<String, Object>> fontes) {}
