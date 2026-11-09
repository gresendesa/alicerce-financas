package tools.alicerce.financeiro.dominio;

import java.util.UUID;
import tools.alicerce.convencoes.Dinheiro;

/** Parte de um gasto: quanto foi para uma categoria, em um âmbito. */
public record Partida(UUID categoria, UUID ambito, Dinheiro valor) {}
