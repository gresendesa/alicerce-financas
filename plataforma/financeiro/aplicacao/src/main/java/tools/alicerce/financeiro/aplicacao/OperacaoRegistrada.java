package tools.alicerce.financeiro.aplicacao;

import java.util.UUID;
import tools.alicerce.convencoes.ReferenciaCurta;

/** A operação que uma escrita gerou: é por ela que o dono desfaz depois. */
public record OperacaoRegistrada(UUID id, ReferenciaCurta referencia, String url) {}
