package eu.vnagy.argotools.junit.kwok;

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

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Testcontainer wrapping the kwok all-in-one cluster image (etcd + kube-apiserver + kwok
 * controller in a single container). Exposes the API server on HTTP port 8080 with no
 * authentication — suitable for local testing only.
 *
 * <p>The container creates a shared Docker {@link #network()} that step containers should
 * join so they can reach kwok via the {@code kwok} hostname. Use {@link #podKubeconfig()}
 * to get the kubeconfig for inside-container use.
 *
 * <p>The container is started lazily by {@link eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor#getKubernetesClient()}
 * and cleaned up by Testcontainers' Ryuk when the JVM exits.
 */
public class KwokContainer extends GenericContainer<KwokContainer> {

    static final int API_PORT = 8080;
    private static final String NETWORK_ALIAS = "kwok";
    private static final String DEFAULT_IMAGE =
            "ghcr.io/kwok-ci/cluster:v0.7.0-k8s.v1.36.0";

    private final Network sharedNetwork;

    public KwokContainer() {
        super(DockerImageName.parse(DEFAULT_IMAGE));
        this.sharedNetwork = Network.newNetwork();
        withNetwork(sharedNetwork);
        withNetworkAliases(NETWORK_ALIAS);
        withExposedPorts(API_PORT);
        waitingFor(Wait.forHttp("/healthz")
                .forPort(API_PORT)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    /** Docker network that step containers should join to reach this cluster by hostname. */
    public Network network() {
        return sharedNetwork;
    }

    /**
     * Kubeconfig for use INSIDE containers that have joined {@link #network()}.
     * Addresses kwok by its network alias — no TLS, no credentials required.
     */
    public String podKubeconfig() {
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: kwok
                  cluster:
                    server: http://%s:%d
                    insecure-skip-tls-verify: true
                contexts:
                - name: kwok-context
                  context:
                    cluster: kwok
                    user: ""
                current-context: kwok-context
                users: []
                """.formatted(NETWORK_ALIAS, API_PORT);
    }

    /**
     * Returns a fabric8 client connected to this cluster via the mapped host port.
     * Uses a synthetic kubeconfig — no TLS, no credentials required.
     */
    public KubernetesClient createClient() {
        String kubeconfig = """
                apiVersion: v1
                kind: Config
                clusters:
                - name: kwok
                  cluster:
                    server: http://%s:%d
                    insecure-skip-tls-verify: true
                contexts:
                - name: kwok-context
                  context:
                    cluster: kwok
                    user: ""
                current-context: kwok-context
                users: []
                """.formatted(getHost(), getMappedPort(API_PORT));
        Config config = Config.fromKubeconfig(kubeconfig);
        return new KubernetesClientBuilder().withConfig(config).build();
    }
}
