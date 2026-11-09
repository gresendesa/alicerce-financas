package tools.alicerce.financeiro.aplicacao;

/** Onde os arquivos ficam. A implementação usa um bucket S3 privado. */
public interface ArmazenamentoComprovantes {

    void guardar(String chave, byte[] conteudo, String tipoMidia);

    byte[] ler(String chave);
}
