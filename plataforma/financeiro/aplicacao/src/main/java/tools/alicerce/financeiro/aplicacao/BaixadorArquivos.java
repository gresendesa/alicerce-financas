package tools.alicerce.financeiro.aplicacao;

import java.net.URI;

/** Baixa um arquivo de um endereço já conferido, sem seguir redirecionamentos. */
public interface BaixadorArquivos {

    /** Vazio se o arquivo passa de {@code limite} bytes; {@link IllegalStateException} se o download falha. */
    java.util.Optional<byte[]> baixar(URI endereco, int limite);
}
