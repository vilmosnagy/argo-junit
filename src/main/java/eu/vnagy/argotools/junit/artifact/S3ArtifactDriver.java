package eu.vnagy.argotools.junit.artifact;

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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.List;
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

    private static final Logger log = LoggerFactory.getLogger(S3ArtifactDriver.class);

    @Override
    public boolean supports(Artifact artifact) {
        return artifact.getS3() != null;
    }

    @Override
    public Path download(Artifact artifact, Path tempDir, KubernetesClient k8sClient, String namespace)
            throws Exception {
        S3Artifact s3 = artifact.getS3();
        log.debug("S3 download: artifact={} bucket={} key={}", artifact.getName(), s3.getBucket(), s3.getKey());
        try (S3Client client = buildClient(s3, k8sClient, namespace)) {
            byte[] content;
            try {
                content = client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(s3.getBucket())
                        .key(s3.getKey())
                        .build()).asByteArray();
            } catch (NoSuchKeyException e) {
                return downloadPrefix(client, s3, artifact, tempDir, e);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "S3 download failed — bucket=" + s3.getBucket()
                        + " key=" + s3.getKey()
                        + " artifact=" + artifact.getName()
                        + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
            }

            if (noArchive(artifact)) {
                Path dest = Files.createTempFile(tempDir, "s3-in-", "");
                Files.write(dest, content);
                return dest;
            } else {
                Path destDir = Files.createTempDirectory(tempDir, "s3-in-");
                try {
                    extractTarGz(content, destDir);
                } catch (IOException e) {
                    // Only fall back to raw when the bytes don't start with gz magic (wrong format).
                    // EOFException or other IOExceptions indicate a genuinely corrupt archive.
                    if (e.getMessage() == null || !e.getMessage().contains("not in the .gz format")) {
                        throw e;
                    }
                    log.debug("S3 download: artifact={} key={} — not a tar.gz, treating as raw",
                            artifact.getName(), s3.getKey());
                    Path dest = Files.createTempFile(tempDir, "s3-in-", "");
                    Files.write(dest, content);
                    return dest;
                }
                try (Stream<Path> ls = Files.list(destDir)) {
                    List<Path> rootEntries = ls.toList();
                    if (rootEntries.isEmpty()) throw new IllegalStateException(
                            "No content extracted from S3 artifact '" + artifact.getName()
                            + "' at key=" + s3.getKey());
                    if (rootEntries.size() == 1 && Files.isRegularFile(rootEntries.get(0)))
                        return rootEntries.get(0);
                    return destDir;
                }
            }
        }
    }

    @Override
    public void upload(Artifact artifact, Path source, KubernetesClient k8sClient, String namespace)
            throws Exception {
        S3Artifact s3 = artifact.getS3();
        log.debug("S3 upload: artifact={} bucket={} key={} source={}", artifact.getName(), s3.getBucket(), s3.getKey(), source);
        try (S3Client client = buildClient(s3, k8sClient, namespace)) {
            if (noArchive(artifact) && Files.isDirectory(source)) {
                uploadDirectory(client, s3, source, artifact.getName());
            } else {
                byte[] content = noArchive(artifact) ? Files.readAllBytes(source) : createTarGz(source);
                try {
                    client.putObject(PutObjectRequest.builder()
                            .bucket(s3.getBucket())
                            .key(s3.getKey())
                            .build(), RequestBody.fromBytes(content));
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "S3 upload failed — bucket=" + s3.getBucket()
                            + " key=" + s3.getKey()
                            + " artifact=" + artifact.getName()
                            + " source=" + source
                            + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
                }
            }
        }
    }

    private static void uploadDirectory(S3Client client, S3Artifact s3, Path dir,
                                        String artifactName) throws IOException {
        String prefix = s3.getKey().endsWith("/") ? s3.getKey() : s3.getKey() + "/";
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String key = prefix + dir.relativize(file);
                log.debug("S3 upload (dir): artifact={} key={}", artifactName, key);
                try {
                    client.putObject(PutObjectRequest.builder()
                            .bucket(s3.getBucket())
                            .key(key)
                            .build(), RequestBody.fromBytes(Files.readAllBytes(file)));
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "S3 upload failed — bucket=" + s3.getBucket()
                            + " key=" + key + " artifact=" + artifactName
                            + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
                }
            }
        }
    }

    private static Path downloadPrefix(S3Client client, S3Artifact s3, Artifact artifact,
                                       Path tempDir, NoSuchKeyException cause) throws IOException {
        String prefix = s3.getKey().endsWith("/") ? s3.getKey() : s3.getKey() + "/";
        log.debug("S3 download prefix: artifact={} bucket={} prefix={}", artifact.getName(), s3.getBucket(), prefix);
        var list = client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(s3.getBucket())
                .prefix(prefix)
                .build());
        if (list.contents().isEmpty()) throw new IllegalStateException(
                "S3 download failed — bucket=" + s3.getBucket()
                + " key=" + s3.getKey()
                + " artifact=" + artifact.getName()
                + ": " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()),
                cause);
        Path destDir = Files.createTempDirectory(tempDir, "s3-in-");
        for (var obj : list.contents()) {
            String relKey = obj.key().substring(prefix.length());
            if (relKey.isEmpty()) continue;
            Path dest = destDir.resolve(relKey).normalize();
            if (!dest.startsWith(destDir)) throw new IllegalStateException(
                    "S3 key path traversal: " + obj.key());
            Files.createDirectories(dest.getParent());
            byte[] bytes = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(s3.getBucket()).key(obj.key()).build()).asByteArray();
            Files.write(dest, bytes);
        }
        return destDir;
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
