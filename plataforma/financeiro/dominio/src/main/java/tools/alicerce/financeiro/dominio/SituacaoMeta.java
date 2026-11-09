package tools.alicerce.financeiro.dominio;

import java.util.Locale;
import java.util.Optional;

/** Em que pé a meta está, pela decisão do dono: o progresso sai dos aportes, nunca daqui. */
public enum SituacaoMeta {
    ATIVA,
    /** O dono deu a meta por cumprida. */
    CONCLUIDA,
    /** O dono desistiu da meta. */
    ENCERRADA;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SituacaoMeta> deCodigo(String codigo) {
        for (SituacaoMeta situacao : values()) {
            if (situacao.codigo().equals(codigo)) {
                return Optional.of(situacao);
            }
        }
        return Optional.empty();
    }
}
