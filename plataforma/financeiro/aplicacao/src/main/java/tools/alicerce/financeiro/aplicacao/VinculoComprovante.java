package tools.alicerce.financeiro.aplicacao;

import java.util.UUID;

/** Uma mudança de lançamento de um comprovante, como fica em {@code efeito_operacao.detalhe}. */
record VinculoComprovante(UUID de, UUID para) {

    private static final String NENHUM = "-";

    String texto() {
        return (de == null ? NENHUM : de) + ">" + (para == null ? NENHUM : para);
    }

    static VinculoComprovante ler(String texto) {
        String[] partes = texto.split(">");
        return new VinculoComprovante(id(partes[0]), id(partes[1]));
    }

    private static UUID id(String texto) {
        return texto.equals(NENHUM) ? null : UUID.fromString(texto);
    }
}
