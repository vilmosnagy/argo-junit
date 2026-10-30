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
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import io.fabric8.kubernetes.api.model.ConfigMap;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
 * argo-server, MinIO and httpbin are skipped. The namespace's default artifact repository can be
 * pointed at an S3 endpoint reachable from the cluster (typically a {@link SiloContainer}
 * attached with {@link #attach}), so templates with output artifacts but no explicit location work.
 *
 * <p>There is one cluster, obtained via {@link #shared()}: it is started on first use and shared
 * by every test class in the JVM. When Testcontainers reuse is enabled
 * ({@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties}), the kind
 * container and its network ({@value #NETWORK_NAME}) are also kept between test runs, so only the
 * first run pays for booting kind and pulling the Argo images. Without reuse, the cluster is
 * removed when the JVM exits. Remove a reused cluster with
 * {@code docker rm -f <container>; docker network rm }{@value #NETWORK_NAME}.
 *
 * <p>Since the cluster outlives a test class, workflows, secrets and config maps created by one
 * test stay around for later ones; {@link #run} deletes a workflow of the same name before
 * submitting.
 *
 * <p>Needs privileged containers; it is meant for local runs, not CI.
 */
public class KindArgoCluster {

    private static final Logger log = LoggerFactory.getLogger(KindArgoCluster.class);

    public static final String NAMESPACE = "argo";
    /** Fixed, so a reused kind container finds its network again on the next run. */
    public static final String NETWORK_NAME = "argo-junit-kind";
    private static final Set<String> SKIPPED_DEPLOYMENTS_AND_SERVICES = Set.of("argo-server", "minio", "httpbin");
    private static final Set<String> TERMINAL_PHASES = Set.of("Succeeded", "Failed", "Error");

    private static KindArgoCluster shared;

    private final KindContainer<?> kind;
    private KubernetesClient k8s;

    /** Returns the shared cluster, starting it (or reconnecting to a reused one) on first call. */
    public static synchronized KindArgoCluster shared() {
        if (shared == null) {
            KindArgoCluster cluster = new KindArgoCluster();
            cluster.start();
            shared = cluster;
        }
        return shared;
    }

    private KindArgoCluster() {
        // The default (newest) node image does not finish kubeadm init under rootless Podman.
        kind = new KindContainer<>(KindContainerVersion.VERSION_1_33_4)
                .withNetwork(fixedNetwork())
                .withReuse(true, "argo-junit")
                // Once a test in the JVM calls Testcontainers.exposeHostPorts, every new container
                // gets a host.testcontainers.internal entry with a per-JVM IP, which would change
                // the reuse hash. Pods in kind don't need it.
                .withCreateContainerCmdModifier(cmd -> {
                    if (cmd.getExtraHosts() == null) return;
                    cmd.withExtraHosts(Arrays.stream(cmd.getExtraHosts())
                            .filter(h -> !h.startsWith("host.testcontainers.internal:"))
                            .toArray(String[]::new));
                });
    }

    private void start() {
        kind.start();
        k8s = new KubernetesClientBuilder().withConfig(Config.fromKubeconfig(kind.getKubeconfig())).build();
        k8s.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(NAMESPACE).endMetadata().build())
                .serverSideApply();
        installArgo();
    }

    public KubernetesClient client() {
        return k8s;
    }

    /**
     * Connects the running {@code container} to the cluster's network, so pods can reach it as
     * {@code alias}. The container may already be on other networks.
     */
    public void attach(GenericContainer<?> container, String alias) {
        DockerClientFactory.instance().client().connectToNetworkCmd()
                .withContainerId(container.getContainerId())
                .withNetworkId(NETWORK_NAME)
                .withContainerNetwork(new ContainerNetwork().withAliases(alias))
                .exec();
    }

    /** Creates (or updates) {@code secret} in the {@value #NAMESPACE} namespace. */
    public void createSecret(Secret secret) {
        k8s.secrets().inNamespace(NAMESPACE).resource(secret).forceConflicts().serverSideApply();
    }

    /** Creates (or updates) {@code configMap} in the {@value #NAMESPACE} namespace. */
    public void createConfigMap(ConfigMap configMap) {
        k8s.configMaps().inNamespace(NAMESPACE).resource(configMap).forceConflicts().serverSideApply();
    }

    /**
     * Creates (or updates) the single resource in {@code yaml}, e.g. a WorkflowTemplate. A
     * namespaced resource lands in the {@value #NAMESPACE} namespace.
     */
    public void apply(String yaml) throws Exception {
        var resource = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, GenericKubernetesResource.class);
        boolean namespaced = k8s.getApiResources(resource.getApiVersion()).getResources().stream()
                .filter(r -> resource.getKind().equals(r.getKind()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown kind " + resource.getKind()))
                .getNamespaced();
        var resources = k8s.genericKubernetesResources(resource.getApiVersion(), resource.getKind());
        (namespaced ? resources.inNamespace(NAMESPACE) : resources)
                .resource(resource).forceConflicts().serverSideApply();
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
     *
     * <p>A workflow with the same {@code metadata.name}, left over from an earlier run on the
     * shared cluster, is deleted first.
     */
    public WorkflowOutcome run(String workflowYaml, Duration timeout) throws Exception {
        var mapper = ArgoWorkflowExecutor.yamlMapper();
        var resource = mapper.readValue(workflowYaml, GenericKubernetesResource.class);
        var workflows = k8s.genericKubernetesResources("argoproj.io/v1alpha1", "Workflow").inNamespace(NAMESPACE);
        String fixedName = resource.getMetadata().getName();
        if (fixedName != null && workflows.withName(fixedName).get() != null) {
            log.debug("Deleting workflow '{}' left over from an earlier run", fixedName);
            workflows.withName(fixedName).withTimeout(1, TimeUnit.MINUTES).delete();
            // Pod names are derived from the workflow name, so the new run would collide with
            // the old pods, which are garbage-collected in the background.
            k8s.pods().inNamespace(NAMESPACE).withLabel("workflows.argoproj.io/workflow", fixedName)
                    .withTimeout(1, TimeUnit.MINUTES).delete();
        }
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

    /**
     * A network with a fixed name, created on first use and never removed, so the reuse hash of
     * the kind container stays the same between runs ({@link Network#newNetwork()} picks a random
     * name each time).
     */
    private static Network fixedNetwork() {
        DockerClient docker = DockerClientFactory.instance().client();
        boolean exists = !docker.listNetworksCmd().withNameFilter(NETWORK_NAME).exec().stream()
                .filter(n -> NETWORK_NAME.equals(n.getName())).toList().isEmpty();
        if (!exists) {
            docker.createNetworkCmd().withName(NETWORK_NAME).exec();
        }
        return new Network() {
            @Override
            public String getId() {
                return NETWORK_NAME;
            }

            @Override
            public void close() {
                // Kept for the reused kind container.
            }
        };
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
            String itemName = item.getMetadata().getName();
            if (("Deployment".equals(kind) || "Service".equals(kind))
                    && SKIPPED_DEPLOYMENTS_AND_SERVICES.contains(itemName)) {
                continue;
            }
            // The quick-start's artifact repositories point at its bundled MinIO, which is not
            // installed, and archive logs — so every pod would error. Start with no repository;
            // useS3ArtifactRepository sets one up when a test needs it.
            if ("ConfigMap".equals(kind) && "artifact-repositories".equals(itemName)) continue;
            if (item instanceof ConfigMap cm && "workflow-controller-configmap".equals(itemName)
                    && cm.getData() != null) {
                cm.getData().remove("artifactRepository");
            }
            k8s.resource(item).inNamespace(NAMESPACE).forceConflicts().serverSideApply();
        }
        log.debug("Waiting for the Argo workflow controller to become ready");
        k8s.apps().deployments().inNamespace(NAMESPACE).withName("workflow-controller")
                .waitUntilReady(5, TimeUnit.MINUTES);
    }
}
