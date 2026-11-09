package tools.alicerce.financeiro.app.arquitetura.arqfixture.convencoes;

import tools.alicerce.financeiro.app.arquitetura.arqfixture.financeiro.persistencia.Repositorio;

/** Violação proposital: convenção dependendo de um módulo de negócio. */
public class ConvencaoComNegocio {
    Repositorio repositorio;
}
