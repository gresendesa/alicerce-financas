package tools.alicerce.convencoes;

import java.util.List;
import java.util.Objects;

/**
 * Falha de uma capacidade no formato único que o assistente aprende uma vez: código, mensagem com
 * acento, o campo que causou o erro e, quando ajudam, candidatos, campos que faltam e uma sugestão
 * do que perguntar ao dono. Os adaptadores só traduzem isto para o protocolo.
 */
public final class ErroTipado extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Uma entidade que o assistente pode escolher: id curto, nome e URL. */
    public record Candidato(String id, String nome, String url) {
        public Candidato {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(nome, "nome");
        }
    }

    private final CodigoErro codigo;
    private final String campo;
    private final transient List<Candidato> candidatos;
    private final transient List<String> camposFaltando;
    private final String sugestao;

    private ErroTipado(CodigoErro codigo, String mensagem, String campo,
            List<Candidato> candidatos, List<String> camposFaltando, String sugestao) {
        super(Objects.requireNonNull(mensagem, "mensagem"));
        this.codigo = Objects.requireNonNull(codigo, "codigo");
        this.campo = campo;
        this.candidatos = List.copyOf(candidatos);
        this.camposFaltando = List.copyOf(camposFaltando);
        this.sugestao = sugestao;
    }

    public static ErroTipado de(CodigoErro codigo, String mensagem) {
        return new ErroTipado(codigo, mensagem, null, List.of(), List.of(), null);
    }

    public static ErroTipado de(CodigoErro codigo, String campo, String mensagem) {
        return new ErroTipado(codigo, mensagem, campo, List.of(), List.of(), null);
    }

    public static ErroTipado validacao(String campo, String mensagem) {
        return new ErroTipado(CodigoErro.VALIDACAO_FALHOU, mensagem, campo, List.of(), List.of(), null);
    }

    public static ErroTipado camposFaltando(List<String> campos, String sugestao) {
        return new ErroTipado(CodigoErro.CAMPOS_FALTANDO, "Faltam campos: " + String.join(", ", campos),
                null, List.of(), campos, sugestao);
    }

    public static ErroTipado referencia(CodigoErro codigo, String campo, String mensagem,
            List<Candidato> candidatos, String sugestao) {
        return new ErroTipado(codigo, mensagem, campo, candidatos, List.of(), sugestao);
    }

    public CodigoErro codigo() {
        return codigo;
    }

    public String mensagem() {
        return getMessage();
    }

    /** O campo da entrada que causou o erro, ou {@code null}. */
    public String campo() {
        return campo;
    }

    public boolean podeRepetir() {
        return codigo.podeRepetir();
    }

    public List<Candidato> candidatos() {
        return candidatos;
    }

    public List<String> camposFaltando() {
        return camposFaltando;
    }

    /** O que o assistente deve perguntar ao dono, ou {@code null}. */
    public String sugestao() {
        return sugestao;
    }
}
