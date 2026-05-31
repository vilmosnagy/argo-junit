package eu.vnagy.argotools.junit.kwok;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Predicate;

/**
 * Test-infrastructure wrapper around {@link KwokContainer} that also installs the Argo
 * Workflows CRDs on startup.
 *
 * <p>Use this in {@code @BeforeAll} / {@code @AfterAll} blocks whenever a test needs to
 * apply {@code WorkflowTemplate} resources or run workflows that use {@code templateRef}:
 *
 * <pre>{@code
 * static ArgoKwok argoKwok;
 *
 * @BeforeAll
 * static void setup() {
 *     argoKwok = new ArgoKwok();
 *     argoKwok.start();
 *     argoKwok.applyYaml("/examples/workflow-template/templates.yaml");
 * }
 *
 * @AfterAll
 * static void tearDown() { argoKwok.stop(); }
 *
 * @Test
 * void myTest() throws Exception {
 *     try (var executor = ArgoWorkflowExecutor.from(path).withKwok(argoKwok.container());
 *          WorkflowRun run = executor.execute()) {
 *         assertThat(run.succeeded(), is(true));
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #start()} starts the container and applies the Argo quick-start manifest (CRDs,
 * RBAC) that was downloaded by the Maven {@code download-maven-plugin} during the build.
 * The version is tracked by {@code argo.quickstart.version} in {@code pom.xml}; updating
 * that property re-downloads the file on the next build.
 *
 * <p>{@link #applyYaml(String)} is intentionally public so tests can apply additional
 * resources (e.g. WorkflowTemplate definitions) after startup.
 */
public class ArgoKwok {

    private static final Logger log = LoggerFactory.getLogger(ArgoKwok.class);

    private final KwokContainer kwok;
    private KubernetesClient k8s;

    public ArgoKwok() {
        kwok = new KwokContainer();
    }

    /**
     * Starts the underlying kwok container and installs the Argo Workflows CRDs (including
     * the {@code WorkflowTemplate} CRD) by applying the quick-start manifest.
     * Blocks until the {@code WorkflowTemplate} API endpoint is reachable.
     */
    public void start() {
        kwok.start();
        k8s = kwok.createClient();
        applyYaml("/argo-install/quick-start-minimal.yaml");
        waitForWorkflowTemplateCrd();
    }

    /** Stops the kwok container and frees its resources. */
    public void stop() {
        kwok.stop();
    }

    /** Returns a new fabric8 client connected to this cluster. */
    public KubernetesClient createClient() {
        return kwok.createClient();
    }

    /**
     * Returns the underlying {@link KwokContainer}, suitable for passing to
     * {@link ArgoWorkflowExecutor#withKwok(KwokContainer)}.
     */
    public KwokContainer container() {
        return kwok;
    }

    /**
     * Applies all resources from a multi-document YAML classpath file to the kwok cluster.
     *
     * @param resourcePath absolute classpath path (e.g. {@code "/examples/workflow-template/templates.yaml"})
     */
    public void applyYaml(String resourcePath) {
        applyYaml(resourcePath, _ -> true);
    }

    /**
     * Applies resources from a multi-document YAML classpath file to the kwok cluster,
     * skipping any resource for which {@code filter} returns {@code false}.
     *
     * @param resourcePath absolute classpath path
     * @param filter       predicate receiving the fully-parsed resource; return {@code false} to skip
     */
    public void applyYaml(String resourcePath, Predicate<HasMetadata> filter) {
        try (InputStream raw = ArgoKwok.class.getResourceAsStream(resourcePath)) {
            if (raw == null) throw new IllegalStateException("Resource not found: " + resourcePath);
            applyYaml(raw, filter);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply " + resourcePath, e);
        }
    }

    /**
     * Applies resources from a multi-document YAML stream to the kwok cluster,
     * skipping any resource for which {@code filter} returns {@code false}.
     *
     * <p>The stream is consumed once; the caller is responsible for closing it.
     * Each YAML document is parsed independently — an unparseable document is logged at
     * WARN and skipped without aborting the rest. YAML merge keys ({@code <<:}) are resolved
     * before handing off to the Kubernetes client.
     *
     * @param content YAML input stream (multi-document, UTF-8)
     * @param filter  predicate receiving the fully-parsed resource; return {@code false} to skip
     */
    public void applyYaml(InputStream content, Predicate<HasMetadata> filter) {
        Yaml snakeYaml = new Yaml();
        for (Object doc : snakeYaml.loadAll(new InputStreamReader(content, StandardCharsets.UTF_8))) {
            if (doc == null) continue;
            // dump() produces clean YAML with merge keys already resolved by SnakeYAML
            byte[] docBytes = new Yaml().dump(doc).getBytes(StandardCharsets.UTF_8);
            List<HasMetadata> items;
            try (InputStream docIs = new ByteArrayInputStream(docBytes)) {
                items = k8s.load(docIs).items();
            } catch (Exception e) {
                log.warn("Skipping unparseable document: {}", e.getMessage());
                continue;
            }
            for (HasMetadata item : items) {
                if (filter.test(item)) applyItem(item);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void applyItem(HasMetadata item) {
        String kind = item.getKind();
        String name = item.getMetadata().getName();
        String ns   = item.getMetadata().getNamespace();
        try {
            if (item instanceof GenericKubernetesResource gkr) {
                String apiVersion = gkr.getApiVersion();
                int slash = apiVersion.indexOf('/');
                String group   = slash >= 0 ? apiVersion.substring(0, slash) : "";
                String version = slash >= 0 ? apiVersion.substring(slash + 1) : apiVersion;
                k8s.genericKubernetesResources(new ResourceDefinitionContext.Builder()
                                .withGroup(group).withVersion(version).withKind(kind)
                                .withNamespaced(true).build())
                        .inNamespace(ns != null ? ns : "default")
                        .resource(gkr).createOrReplace();
            } else {
                k8s.resource(item).createOrReplace();
            }
        } catch (Exception e) {
            log.debug("Skipping {}/{}: {}", kind, name, e.getMessage());
        }
    }

    private void waitForWorkflowTemplateCrd() {
        // Phase 1: CRD object in etcd
        boolean found = false;
        for (int i = 0; i < 30; i++) {
            if (k8s.apiextensions().v1().customResourceDefinitions()
                    .withName("workflowtemplates.argoproj.io").get() != null) {
                found = true;
                break;
            }
            sleep(500);
        }
        if (!found) throw new IllegalStateException(
                "WorkflowTemplate CRD not found after 15s");

        // Phase 2: API endpoint registered (kube-apiserver may lag behind etcd write)
        for (int i = 0; i < 30; i++) {
            try {
                k8s.genericKubernetesResources(ArgoWorkflowExecutor.WORKFLOW_TEMPLATE_CTX)
                        .inNamespace("default").list();
                return;
            } catch (Exception ignored) {
                sleep(500);
            }
        }
        throw new IllegalStateException(
                "WorkflowTemplate API endpoint not available after 15s");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
