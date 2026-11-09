package tools.alicerce.financeiro.app.arquitetura.arqfixture.planos;

import tools.alicerce.financeiro.app.arquitetura.arqfixture.financeiro.persistencia.Repositorio;

/** Violação proposital: um módulo de negócio importando outro. */
public class PlanoQueLeFinanceiro {
    Repositorio repositorio;
}
