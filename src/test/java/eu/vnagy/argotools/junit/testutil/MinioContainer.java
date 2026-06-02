package eu.vnagy.argotools.junit.testutil;

/*-
 * #%L
 * Argo JUnit
 * %%
 * Copyright (C) 2026 Vilmos Szabó-Nagy
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.util.Base64;
import java.util.Map;

/** Testcontainer wrapping MinIO for S3-compatible storage in unit tests. */
public class MinioContainer extends GenericContainer<MinioContainer> {

    public static final String DEFAULT_ACCESS_KEY = "minioadmin";
    public static final String DEFAULT_SECRET_KEY = "minioadmin";
    private static final int MINIO_PORT = 9000;

    public MinioContainer() {
        super("minio/minio:latest");
        withCommand("server", "/data", "--console-address", ":9001");
        withExposedPorts(MINIO_PORT);
        withEnv("MINIO_ROOT_USER", DEFAULT_ACCESS_KEY);
        withEnv("MINIO_ROOT_PASSWORD", DEFAULT_SECRET_KEY);
        waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT).forStatusCode(200));
    }

    /** Returns {@code host:port} reachable from the test JVM. */
    public String endpoint() {
        return getHost() + ":" + getMappedPort(MINIO_PORT);
    }

    public S3Client createClient() {
        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(DEFAULT_ACCESS_KEY, DEFAULT_SECRET_KEY)))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + endpoint()))
                .build();
    }

    public void createBucket(String bucket) {
        try (S3Client client = createClient()) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    /** Returns a fabric8 Secret with MinIO credentials, ready to apply to kwok. */
    public Secret credentialsSecret(String secretName, String accessKeyField, String secretKeyField) {
        return new SecretBuilder()
                .withNewMetadata().withName(secretName).endMetadata()
                .withData(Map.of(
                        accessKeyField, Base64.getEncoder().encodeToString(DEFAULT_ACCESS_KEY.getBytes()),
                        secretKeyField, Base64.getEncoder().encodeToString(DEFAULT_SECRET_KEY.getBytes())
                ))
                .build();
    }
}
