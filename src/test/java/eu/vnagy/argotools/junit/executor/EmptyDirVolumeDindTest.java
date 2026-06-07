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
import eu.vnagy.argotools.junit.EmptyDirVolumeBase;
import eu.vnagy.argotools.junit.testutil.DindContainerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;

/**
 * Runs the {@code emptyDir} volume-mount scenario through a Docker-in-Docker (DinD) daemon.
 *
 * <p>The {@code emptyDir} code path uses {@code withFileSystemBind} with an intentionally empty
 * local directory. When the remote daemon receives that bind-mount request, it also creates an
 * empty directory — which is the correct result. This test is expected to <b>pass</b> even before
 * any DinD-related fix, documenting that {@code emptyDir} is unaffected by the bind-mount bug.
 *
 * <p><b>Requirements:</b> privileged containers must be available. Works with rootful Docker or
 * rootful Podman. Rootless Podman cannot grant the kernel capabilities needed to run a nested
 * Docker daemon; the tests fail if DinD cannot start.
 */
class EmptyDirVolumeDindTest extends EmptyDirVolumeBase {

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
        dind.start();

        dindClient = DockerClientProviderStrategy.getClientForConfig(
                TransportConfig.builder()
                        .dockerHost(new URI("tcp://" + dind.getHost() + ":" + dind.getMappedPort(2375)))
                        .build());

        dind.execInContainer("docker", "pull", "alpine:3.21");
    }

    @AfterAll
    static void tearDownDind() {
        if (dind != null) dind.stop();
    }

    @Override
    protected ArgoWorkflowExecutor configure(ArgoWorkflowExecutor executor) {
        return executor.withContainerFactory(DindContainerFactory.forClient(dindClient));
    }
}
