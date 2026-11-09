package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/** Resultado de {@code definir_meio_pagamento}. */
public record MeioDefinido(String id, String nome, String tipo, List<String> aliases, String conta, boolean padrao,
        boolean arquivado) {}
