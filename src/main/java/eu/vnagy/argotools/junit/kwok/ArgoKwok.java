package eu.vnagy.argotools.junit.kwok;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

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
     * Applies a multi-document YAML file from the classpath to the kwok cluster.
     * Resources that fail to apply (e.g. namespaced resources for a namespace that doesn't
     * exist yet) are logged at DEBUG and silently skipped.
     *
     * @param resourcePath absolute classpath path (e.g. {@code "/examples/workflow-template/templates.yaml"})
     */
    public void applyYaml(String resourcePath) {
        try (InputStream is = ArgoKwok.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + resourcePath);
            k8s.load(is).items().forEach(item -> {
                String kind = item.getKind();
                String name = item.getMetadata().getName();
                String ns = item.getMetadata().getNamespace();
                try {
                    if (item instanceof GenericKubernetesResource gkr) {
                        String apiVersion = gkr.getApiVersion();
                        int slash = apiVersion.indexOf('/');
                        String group = slash >= 0 ? apiVersion.substring(0, slash) : "";
                        String version = slash >= 0 ? apiVersion.substring(slash + 1) : apiVersion;
                        var ctx = new io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext.Builder()
                                .withGroup(group).withVersion(version).withKind(kind)
                                .withNamespaced(true).build();
                        k8s.genericKubernetesResources(ctx)
                                .inNamespace(ns != null ? ns : "default")
                                .resource(gkr).createOrReplace();
                    } else {
                        k8s.resource(item).createOrReplace();
                    }
                } catch (Exception e) {
                    log.debug("Skipping {}/{}: {}", kind, name, e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply " + resourcePath, e);
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

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
