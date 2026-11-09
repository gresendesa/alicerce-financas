package tools.alicerce.financeiro.persistencia;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import tools.alicerce.financeiro.aplicacao.ArmazenamentoComprovantes;

/**
 * Comprovantes num bucket S3 privado. A criptografia e o versionamento são do bucket. As credenciais
 * vêm do ambiente, e só são lidas no primeiro uso: a aplicação sobe sem elas.
 */
@Component
class ArmazenamentoS3 implements ArmazenamentoComprovantes {

    private final S3Client s3;
    private final String bucket;

    ArmazenamentoS3(@Value("${alicerce.financeiro.comprovantes.bucket}") String bucket,
            @Value("${alicerce.financeiro.comprovantes.regiao}") String regiao,
            @Value("${alicerce.financeiro.comprovantes.endpoint}") String endpoint) {
        this.bucket = bucket;
        S3ClientBuilder construtor = S3Client.builder().region(Region.of(regiao))
                .httpClient(UrlConnectionHttpClient.create());
        if (!endpoint.isBlank()) {
            construtor.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        this.s3 = construtor.build();
    }

    @Override
    public void guardar(String chave, byte[] conteudo, String tipoMidia) {
        exigirBucket();
        s3.putObject(p -> p.bucket(bucket).key(chave).contentType(tipoMidia), RequestBody.fromBytes(conteudo));
    }

    @Override
    public byte[] ler(String chave) {
        exigirBucket();
        return s3.getObjectAsBytes(g -> g.bucket(bucket).key(chave)).asByteArray();
    }

    private void exigirBucket() {
        if (bucket.isBlank()) {
            throw new IllegalStateException("Bucket de comprovantes não configurado (COMPROVANTES_BUCKET)");
        }
    }
}
