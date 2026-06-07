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
import eu.vnagy.argotools.junit.VolumeMountBase;
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
 * Runs the ConfigMap and Secret volume-mount scenarios through a Docker-in-Docker (DinD) daemon.
 *
 * <p>This reproduces the bind-mount bug for ConfigMap/Secret volumes: {@code PodRun} materializes
 * ConfigMap and Secret data to a temp directory on the JVM filesystem, then passes that path to
 * the remote daemon via {@code withFileSystemBind}. The daemon resolves the path on its own
 * filesystem, finds nothing, and mounts an empty directory — the ConfigMap keys are invisible
 * inside the container.
 *
 * <p>Both tests are expected to <b>fail</b> until {@code withFileSystemBind} is replaced with
 * {@code withCopyFileToContainer} for ConfigMap/Secret volumes in {@code PodRun}.
 *
 * <p><b>Requirements:</b> privileged containers must be available. Works with rootful Docker or
 * rootful Podman. Rootless Podman cannot grant the kernel capabilities needed to run a nested
 * Docker daemon; the tests skip automatically in that case.
 */
class VolumeMountDindTest extends VolumeMountBase {

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

        dind.execInContainer("docker", "pull", "alpine:3.23");
        dind.execInContainer("docker", "pull", "busybox");
    }

    @AfterAll
    static void tearDownDind() {
        if (dind != null) dind.stop();
    }

    @Override
    protected ArgoWorkflowExecutor configure(ArgoWorkflowExecutor executor) {
        assumeTrue(dindClient != null, "DinD unavailable — skipped by setUpDind");
        return executor.withDockerClient(dindClient);
    }
}
