package eu.vnagy.argotools.junit.executor;

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

import com.github.dockerjava.api.DockerClient;
import eu.vnagy.argotools.junit.S3DirectoryArtifactBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the S3 directory artifact scenarios through a Docker-in-Docker (DinD) daemon.
 *
 * <p>This reproduces the bind-mount bug that occurs when the JVM and the Docker daemon do not
 * share a filesystem (the typical GitLab CI + DinD setup): {@code PodRun} materializes an S3
 * directory artifact to a temp path on the JVM, then passes that path string to the remote daemon
 * via {@code withFileSystemBind}. The daemon resolves the path on <em>its own</em> filesystem,
 * finds nothing, and silently mounts an empty directory — the artifact files are invisible.
 *
 * <p>The test is expected to <b>fail</b> until {@code withFileSystemBind} is replaced with
 * {@code withCopyFileToContainer} for directory artifacts in {@code PodRun}.
 *
 * <p><b>Requirements:</b> privileged containers must be available. Works with rootful Docker or
 * rootful Podman. Rootless Podman cannot grant the kernel capabilities needed to run a nested
 * Docker daemon; the tests skip automatically in that case.
 */
class S3DirectoryArtifactDindTest extends S3DirectoryArtifactBase {

    static GenericContainer<?> dind;
    static DockerClient dindClient;

    @BeforeAll
    static void setUpDind() throws Exception {
        dind = new GenericContainer<>(DockerImageName.parse("docker:27-dind"))
                .withPrivilegedMode(true)
                .withEnv("DOCKER_TLS_CERTDIR", "")
                .withExposedPorts(2375)
                .waitingFor(Wait.forLogMessage(".*API listen on.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        try {
            dind.start();
        } catch (Exception e) {
            assumeTrue(false,
                    "Skipped: cannot start DinD (needs rootful Docker / rootful Podman with --privileged): "
                            + e.getMessage());
            return;
        }

        dindClient = DockerClientProviderStrategy.getClientForConfig(
                TransportConfig.builder()
                        .dockerHost(new URI("tcp://" + dind.getHost() + ":" + dind.getMappedPort(2375)))
                        .build());

        // Pre-pull the step container image into DinD's local image cache so the test does not
        // depend on Docker Hub being reachable at assertion time.
        dind.execInContainer("docker", "pull", "alpine:3");
    }

    @AfterAll
    static void tearDownDind() {
        if (dind != null) dind.stop();
    }

    /**
     * Routes all step-container operations through the DinD daemon. The JVM and that daemon do
     * not share a filesystem, so {@code withFileSystemBind} produces an empty directory and the
     * assertions on artifact contents fail — reproducing the bug.
     */
    @Override
    protected ArgoWorkflowExecutor configure(ArgoWorkflowExecutor executor) {
        assumeTrue(dindClient != null, "DinD unavailable — skipped by setUpDind");
        return executor.withDockerClient(dindClient);
    }
}
