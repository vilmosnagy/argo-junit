package eu.vnagy.argotools.junit.artifact;

import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1SecretKeySelector;
import eu.vnagy.argotools.junit.model.S3Artifact;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.stream.Stream;

/**
 * {@link ArtifactDriver} for S3-compatible object storage (AWS S3, MinIO, GCS interop, etc.).
 *
 * <p>Credentials are resolved from Kubernetes Secrets in kwok via the
 * {@code accessKeySecret} and {@code secretKeySecret} selectors on the artifact. When
 * {@code insecure: true} is set the endpoint is reached over plain HTTP; path-style access
 * is always enabled so that MinIO and other non-AWS endpoints work without DNS tricks.
 *
 * <p>Archive handling follows Argo's default: content is tar.gz-compressed before upload
 * and decompressed on download unless {@code archive.none: {}} is set on the artifact.
 */
public class S3ArtifactDriver implements ArtifactDriver {

    @Override
    public boolean supports(Artifact artifact) {
        return artifact.getS3() != null;
    }

    @Override
    public Path download(Artifact artifact, Path tempDir, KubernetesClient k8sClient, String namespace)
            throws Exception {
        S3Artifact s3 = artifact.getS3();
        try (S3Client client = buildClient(s3, k8sClient, namespace)) {
            byte[] content = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(s3.getBucket())
                    .key(s3.getKey())
                    .build()).asByteArray();

            if (noArchive(artifact)) {
                Path dest = Files.createTempFile(tempDir, "s3-in-", "");
                Files.write(dest, content);
                return dest;
            } else {
                Path destDir = Files.createTempDirectory(tempDir, "s3-in-");
                extractTarGz(content, destDir);
                try (Stream<Path> ls = Files.list(destDir)) {
                    return ls.findFirst().orElseThrow(() -> new IllegalStateException(
                            "No content extracted from S3 artifact '" + artifact.getName()
                            + "' at key=" + s3.getKey()));
                }
            }
        }
    }

    @Override
    public void upload(Artifact artifact, Path source, KubernetesClient k8sClient, String namespace)
            throws Exception {
        S3Artifact s3 = artifact.getS3();
        byte[] content = noArchive(artifact) ? Files.readAllBytes(source) : createTarGz(source);
        try (S3Client client = buildClient(s3, k8sClient, namespace)) {
            client.putObject(PutObjectRequest.builder()
                    .bucket(s3.getBucket())
                    .key(s3.getKey())
                    .build(), RequestBody.fromBytes(content));
        }
    }

    private static boolean noArchive(Artifact artifact) {
        return artifact.getArchive() != null && artifact.getArchive().getNone() != null;
    }

    private static S3Client buildClient(S3Artifact s3, KubernetesClient k8sClient, String namespace) {
        if (k8sClient == null) throw new IllegalStateException(
                "S3 artifact credentials require a Kubernetes client — "
                + "call ArgoWorkflowExecutor.withKwok() or getKubernetesClient() before execute()");

        String accessKey = resolveSecret(k8sClient, namespace, s3.getAccessKeySecret());
        String secretKey = resolveSecret(k8sClient, namespace, s3.getSecretKeySecret());

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .region(Region.of(s3.getRegion() != null ? s3.getRegion() : "us-east-1"));

        if (s3.getEndpoint() != null) {
            String scheme = Boolean.TRUE.equals(s3.getInsecure()) ? "http" : "https";
            builder.endpointOverride(URI.create(scheme + "://" + s3.getEndpoint()));
        }

        return builder.build();
    }

    private static String resolveSecret(KubernetesClient client, String namespace,
                                        IoK8sApiCoreV1SecretKeySelector ref) {
        if (ref == null) throw new IllegalArgumentException(
                "Secret credential reference is null — check accessKeySecret / secretKeySecret on the artifact");
        var secret = client.secrets().inNamespace(namespace).withName(ref.getName()).get();
        if (secret == null) throw new IllegalStateException(
                "Secret '" + ref.getName() + "' not found in namespace '" + namespace + "'");
        var data = secret.getData();
        if (data == null || !data.containsKey(ref.getKey())) throw new IllegalStateException(
                "Key '" + ref.getKey() + "' not found in Secret '" + ref.getName() + "'");
        return new String(Base64.getDecoder().decode(data.get(ref.getKey())));
    }

    private static byte[] createTarGz(Path source) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            addToTar(tar, source, source.getFileName().toString());
        }
        return baos.toByteArray();
    }

    private static void addToTar(TarArchiveOutputStream tar, Path path, String entryName)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), entryName);
        tar.putArchiveEntry(entry);
        if (Files.isRegularFile(path)) {
            Files.copy(path, tar);
        }
        tar.closeArchiveEntry();
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path).sorted()) {
                for (Path child : children.toList()) {
                    addToTar(tar, child, entryName + "/" + child.getFileName());
                }
            }
        }
    }

    private static void extractTarGz(byte[] content, Path destDir) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(content);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(bais);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) throw new IllegalStateException(
                        "Tar path traversal detected: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
