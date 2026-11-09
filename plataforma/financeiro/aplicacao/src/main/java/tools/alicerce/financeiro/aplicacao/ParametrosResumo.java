package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/**
 * Os parâmetros de um resumo já resolvidos, como o retrato os guarda: refazer o cálculo com eles dá o
 * mesmo relatório, mesmo que o âmbito mude de nome ou seja arquivado.
 *
 * @param ambitoId o âmbito filtrado; nulo não filtra
 * @param ambito o nome dele quando o retrato foi gravado
 * @param profundidade o nível em que as categorias são somadas, a partir de 1; nulo mantém cada categoria
 */
public record ParametrosResumo(String de, String ate, List<String> agruparPor, String ambitoId, String ambito,
        Integer profundidade, String visao, boolean compararAnterior) {}
