package tools.alicerce.financeiro.aplicacao;

import java.util.Locale;

/** Entidades mutáveis: cada versão fica gravada em {@code <entidade>_versao}, para o desfazer restaurar. */
public enum EntidadeVersionada {
    INSTITUICAO,
    CONTA,
    CARTAO_CREDITO,
    AMBITO,
    MEIO_PAGAMENTO,
    COMPROMISSO,
    META;

    /** O nome gravado em {@code efeito_operacao.entidade}. */
    public String entidade() {
        return name().toLowerCase(Locale.ROOT);
    }
}
