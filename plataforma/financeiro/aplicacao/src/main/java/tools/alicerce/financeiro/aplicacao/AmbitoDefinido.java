package tools.alicerce.financeiro.aplicacao;

import java.util.List;

/** Resultado de {@code definir_ambito}. */
public record AmbitoDefinido(String id, String nome, List<String> aliases, boolean padrao, boolean arquivado,
        List<String> padraoDosMeios) {}
