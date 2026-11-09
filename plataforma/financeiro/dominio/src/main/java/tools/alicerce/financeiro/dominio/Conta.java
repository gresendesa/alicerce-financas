package tools.alicerce.financeiro.dominio;

import java.util.List;
import java.util.UUID;

/** Uma conta do plano: onde o dinheiro fica, uma categoria ou uma fonte de renda. */
public record Conta(UUID id, ClasseConta classe, TipoConta tipo, String nome, List<String> aliases, UUID contaPai,
        UUID instituicao, String moeda, boolean arquivada, long versao) {

    public Conta {
        aliases = List.copyOf(aliases);
    }
}
