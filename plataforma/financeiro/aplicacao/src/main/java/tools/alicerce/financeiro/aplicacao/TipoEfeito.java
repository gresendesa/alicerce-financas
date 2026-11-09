package tools.alicerce.financeiro.aplicacao;

import java.util.Locale;

/** O que uma operação fez, efeito por efeito; o desfazer aplica o inverso de cada um. */
public enum TipoEfeito {
    LANCAMENTO_CRIADO,
    LANCAMENTO_ESTORNADO,
    CHAVE_FISCAL_MOVIDA,
    ENTIDADE_CRIADA,
    ENTIDADE_ALTERADA,
    OPERACAO_DESFEITA,
    OPERACAO_RESTABELECIDA,
    COMPROVANTE_VINCULADO,
    TOKEN_ANEXO_EMITIDO,
    RETRATO_GRAVADO,
    FATURA_CRIADA,
    CONFERENCIA_REGISTRADA,
    OCORRENCIA_REGISTRADA,
    DECLARACAO_REGISTRADA,
    LINK_CRIADO,
    LINK_REVOGADO;

    /** O nome gravado em {@code efeito_operacao.tipo}. */
    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TipoEfeito deCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }
}
