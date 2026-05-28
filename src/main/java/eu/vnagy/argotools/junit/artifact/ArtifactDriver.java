package eu.vnagy.argotools.junit.artifact;

import eu.vnagy.argotools.junit.model.Artifact;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.nio.file.Path;

/**
 * Strategy interface for transferring workflow artifacts to and from external storage.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}; register them in
 * {@code META-INF/services/eu.vnagy.argotools.junit.artifact.ArtifactDriver}.
 *
 * <p>The executor calls {@link #supports} on each registered driver in order and uses the
 * first match. Built-in: {@link S3ArtifactDriver} (handles {@code s3:} locations).
 */
public interface ArtifactDriver {

    /** Returns {@code true} if this driver handles the artifact's location type. */
    boolean supports(Artifact artifact);

    /**
     * Downloads the artifact from its external location into {@code tempDir} and returns
     * the host-side path of the resulting file or directory.
     *
     * <p>If the artifact uses the default archive strategy (tar.gz), the driver decompresses
     * the download and returns the extracted entry. If {@code archive.none} is set, the raw
     * bytes are written to a temp file and that path is returned.
     *
     * @param artifact  artifact descriptor (location, key, credential Secret refs)
     * @param tempDir   per-run scratch directory; the driver creates its own entries inside it
     * @param k8sClient fabric8 client pointed at kwok; used to resolve Secret-backed credentials
     * @param namespace Kubernetes namespace for Secret lookups
     * @return host-side path of the downloaded content
     */
    Path download(Artifact artifact, Path tempDir, KubernetesClient k8sClient, String namespace)
            throws Exception;

    /**
     * Uploads {@code source} to the artifact's external location.
     *
     * <p>If the default archive strategy applies, the driver wraps {@code source} in a
     * tar.gz before uploading. If {@code archive.none} is set, {@code source} (which must
     * be a regular file) is uploaded as-is.
     *
     * @param artifact  artifact descriptor
     * @param source    host-side file or directory extracted from the container
     * @param k8sClient fabric8 client pointed at kwok; used to resolve Secret-backed credentials
     * @param namespace Kubernetes namespace for Secret lookups
     */
    void upload(Artifact artifact, Path source, KubernetesClient k8sClient, String namespace)
            throws Exception;
}
