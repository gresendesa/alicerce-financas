package tools.alicerce.financeiro.app;

import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** S3 compatível no Testcontainers, com o bucket dos comprovantes criado. */
@TestConfiguration(proxyBeanMethods = false)
public class MinioDeTeste {

    public static final String BUCKET = "comprovantes-teste";

    @Bean(initMethod = "start", destroyMethod = "stop")
    MinIOContainer minio() {
        return new MinIOContainer(DockerImageName.parse(System.getProperty("alicerce.imagem.minio"))
                .asCompatibleSubstituteFor("minio/minio"));
    }

    @Bean
    S3Client s3DeTeste(MinIOContainer minio) {
        S3Client s3 = S3Client.builder().region(Region.US_EAST_1).httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(minio.getS3URL())).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .build();
        s3.createBucket(c -> c.bucket(BUCKET));
        return s3;
    }

    @Bean
    DynamicPropertyRegistrar propriedadesMinio(MinIOContainer minio, S3Client s3DeTeste) {
        // A aplicação lê as credenciais do ambiente, como em produção.
        System.setProperty("aws.accessKeyId", minio.getUserName());
        System.setProperty("aws.secretAccessKey", minio.getPassword());
        return registro -> {
            registro.add("alicerce.financeiro.comprovantes.bucket", () -> BUCKET);
            registro.add("alicerce.financeiro.comprovantes.endpoint", minio::getS3URL);
        };
    }
}
