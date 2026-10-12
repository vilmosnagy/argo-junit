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

import com.dajudge.kindcontainer.KindContainer;
import com.dajudge.kindcontainer.KindContainerVersion;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A kind cluster running the real Argo Workflows controller, for checking that argo-junit's
 * behaviour matches Argo's.
 *
 * <p>Installs the same {@code quick-start-minimal.yaml} that {@code ArgoKwok} uses for its CRDs
 * into the {@value #NAMESPACE} namespace — only the workflow controller and its RBAC; the bundled
 * argo-server, MinIO and httpbin are skipped. The namespace's default artifact repository is
 * pointed at an S3 endpoint reachable from the cluster (typically a {@link SiloContainer} on the
 * same {@link Network}), so templates with output artifacts but no explicit location work.
 *
 * <p>Needs privileged containers and pulls the Argo images from quay.io on every start; it is
 * meant for local runs, not CI.
 */
public class KindArgoCluster {

    private static final Logger log = LoggerFactory.getLogger(KindArgoCluster.class);

    public static final String NAMESPACE = "argo";
    private static final Set<String> SKIPPED_DEPLOYMENTS_AND_SERVICES = Set.of("argo-server", "minio", "httpbin");
    private static final Set<String> TERMINAL_PHASES = Set.of("Succeeded", "Failed", "Error");

    private final KindContainer<?> kind;
    private KubernetesClient k8s;

    /**
     * @param network the network the cluster node joins, so pods can reach other containers on it
     *                by their network alias
     */
    public KindArgoCluster(Network network) {
        // The default (newest) node image does not finish kubeadm init under rootless Podman.
        kind = new KindContainer<>(KindContainerVersion.VERSION_1_33_4).withNetwork(network);
    }

    public void start() {
        kind.start();
        k8s = new KubernetesClientBuilder().withConfig(Config.fromKubeconfig(kind.getKubeconfig())).build();
        k8s.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(NAMESPACE).endMetadata().build())
                .serverSideApply();
        installArgo();
    }

    public void stop() {
        if (k8s != null) k8s.close();
        kind.stop();
    }

    public KubernetesClient client() {
        return k8s;
    }

    /** Creates {@code secret} in the {@value #NAMESPACE} namespace. */
    public void createSecret(Secret secret) {
        k8s.secrets().inNamespace(NAMESPACE).resource(secret).serverSideApply();
    }

    /**
     * Makes {@code s3} the default artifact repository of the {@value #NAMESPACE} namespace.
     *
     * @param endpoint   {@code host:port} as seen from inside the cluster
     * @param bucket     bucket output artifacts are written to
     * @param secretName secret holding {@code access-key} / {@code secret-key}
     */
    public void useS3ArtifactRepository(String endpoint, String bucket, String secretName) {
        String repository = """
                s3:
                  endpoint: %s
                  bucket: %s
                  insecure: true
                  accessKeySecret:
                    name: %s
                    key: access-key
                  secretKeySecret:
                    name: %s
                    key: secret-key
                """.formatted(endpoint, bucket, secretName, secretName);
        k8s.configMaps().inNamespace(NAMESPACE).resource(new ConfigMapBuilder()
                        .withNewMetadata()
                        .withName("artifact-repositories")
                        .addToAnnotations("workflows.argoproj.io/default-artifact-repository", "default")
                        .endMetadata()
                        .withData(Map.of("default", repository))
                        .build())
                .forceConflicts().serverSideApply();
    }

    /**
     * Submits the workflow in {@code workflowYaml} and blocks until it reaches a terminal phase.
     *
     * <p>Takes the YAML rather than a {@code Workflow} model: serializing the generated model
     * emits empty lists (e.g. {@code steps: []}) that Argo's CRD validation rejects.
     */
    public WorkflowOutcome run(String workflowYaml, Duration timeout) throws Exception {
        var mapper = ArgoWorkflowExecutor.yamlMapper();
        var resource = mapper.readValue(workflowYaml, GenericKubernetesResource.class);
        var workflows = k8s.genericKubernetesResources("argoproj.io/v1alpha1", "Workflow").inNamespace(NAMESPACE);
        String name = workflows.resource(resource).create().getMetadata().getName();

        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            GenericKubernetesResource current = workflows.withName(name).get();
            Map<String, Object> status = current.get("status");
            String phase = status != null ? (String) status.get("phase") : null;
            if (phase != null && TERMINAL_PHASES.contains(phase)) {
                String summary = mapper.writeValueAsString(status);
                log.debug("Workflow '{}' finished with phase {}:\n{}", name, phase, summary);
                return new WorkflowOutcome("Succeeded".equals(phase), summary);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Workflow '" + name + "' did not finish within " + timeout
                        + "; last status: " + mapper.writeValueAsString(status));
            }
            Thread.sleep(1000);
        }
    }

    private void installArgo() {
        List<HasMetadata> items;
        try (InputStream manifest = KindArgoCluster.class.getResourceAsStream("/argo-install/quick-start-minimal.yaml")) {
            if (manifest == null) throw new IllegalStateException("Resource not found: /argo-install/quick-start-minimal.yaml");
            items = k8s.load(manifest).items();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load the Argo quick-start manifest", e);
        }
        for (HasMetadata item : items) {
            String kind = item.getKind();
            if (("Deployment".equals(kind) || "Service".equals(kind))
                    && SKIPPED_DEPLOYMENTS_AND_SERVICES.contains(item.getMetadata().getName())) {
                continue;
            }
            k8s.resource(item).inNamespace(NAMESPACE).forceConflicts().serverSideApply();
        }
        log.debug("Waiting for the Argo workflow controller to become ready");
        k8s.apps().deployments().inNamespace(NAMESPACE).withName("workflow-controller")
                .waitUntilReady(5, TimeUnit.MINUTES);
    }
}
