package eu.vnagy.argotools.junit.executor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLAnchorReplayingFactory;
import eu.vnagy.argotools.junit.artifact.ArtifactDriver;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1Volume;
import eu.vnagy.argotools.junit.model.Parameter;
import eu.vnagy.argotools.junit.model.RetryStrategy;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.TemplateRef;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.model.WorkflowStep;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.ServiceLoader;

/**
 * Parses an Argo Workflow definition and executes it locally using Testcontainers.
 *
 * <p>Basic usage — no Kubernetes:
 * <pre>{@code
 * try (WorkflowRun run = ArgoWorkflowExecutor.from(path).execute()) {
 *     assertThat(run.succeeded(), is(true));
 * }
 * }</pre>
 *
 * <p>With an executor-owned kwok cluster (started and stopped by this executor):
 * <pre>{@code
 * try (ArgoWorkflowExecutor executor = ArgoWorkflowExecutor.from(path)) {
 *     executor.getKubernetesClient().configMaps()...create();
 *     try (WorkflowRun run = executor.execute()) { ... }
 * }
 * }</pre>
 *
 * <p>With a shared kwok cluster managed externally (e.g. a JUnit {@code @BeforeAll} field):
 * <pre>{@code
 * KwokContainer kwok = new KwokContainer();
 * kwok.start();
 * // ... reuse across many tests ...
 * try (WorkflowRun run = ArgoWorkflowExecutor.from(path).withKwok(kwok).execute()) { ... }
 * }</pre>
 *
 * <p>Closing the executor releases only resources it owns: a kwok container started via
 * {@link #getKubernetesClient()} and/or a Docker network created because none was supplied.
 * Externally-managed resources passed via {@link #withKwok} or {@link #withNetwork} are
 * never touched by {@link #close()}.
 */
public class ArgoWorkflowExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArgoWorkflowExecutor.class);

    // YAMLAnchorReplayingFactory (added in Jackson 2.19) replays anchor node tokens on alias
    // references, fixing both POJO-field aliases and string-field aliases transparently.
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLAnchorReplayingFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final ResourceDefinitionContext WORKFLOW_TEMPLATE_CTX =
            new ResourceDefinitionContext.Builder()
                    .withGroup("argoproj.io").withVersion("v1alpha1").withKind("WorkflowTemplate")
                    .withNamespaced(true).build();

    private final Workflow workflow;
    private String namespace = "default";

    // Kubernetes connectivity — non-null when kwok is in play (owned or external)
    private KubernetesClient k8sClient;
    private String podKubeconfig;

    // Docker network for step-container connectivity — always resolved before execution
    private Network network;

    // Resources owned by this executor; closed by close(), null when externally supplied
    private KwokContainer ownedKwok;
    private Network ownedNetwork;

    private volatile boolean closed = false;

    private ArgoWorkflowExecutor(Workflow workflow) {
        this.workflow = workflow;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Returns the configured YAML {@link ObjectMapper} used internally to parse workflow files.
     *
     * <p>Use this when you need to parse a workflow YAML yourself before calling
     * {@link #from(Workflow)} — for example, to override the entrypoint or inject parameters
     * before execution. The mapper uses {@link YAMLAnchorReplayingFactory}, which correctly
     * resolves YAML anchor aliases for both POJO fields and plain string fields.
     *
     * <pre>{@code
     * Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yamlString, Workflow.class);
     * wf.getSpec().setEntrypoint("my-entrypoint");
     * try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) { ... }
     * }</pre>
     *
     * @return the shared, configured YAML {@link ObjectMapper}
     */
    public static ObjectMapper yamlMapper() {
        return YAML;
    }

    /**
     * Creates an executor from a workflow YAML file on disk.
     *
     * @param workflowFile path to the Argo Workflow YAML file
     * @throws IllegalArgumentException if the file cannot be read or parsed
     */
    public static ArgoWorkflowExecutor from(Path workflowFile) {
        try {
            return new ArgoWorkflowExecutor(YAML.readValue(Files.newInputStream(workflowFile), Workflow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read workflow file: " + workflowFile, e);
        }
    }

    /**
     * Creates an executor from a workflow YAML string.
     *
     * @param workflowYaml the Argo Workflow definition as a YAML string
     * @throws IllegalArgumentException if the YAML cannot be parsed
     */
    public static ArgoWorkflowExecutor from(String workflowYaml) {
        try {
            return new ArgoWorkflowExecutor(YAML.readValue(workflowYaml, Workflow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse workflow YAML", e);
        }
    }

    /**
     * Creates an executor from a pre-built {@link Workflow} model object.
     *
     * @param workflow the workflow to execute
     */
    public static ArgoWorkflowExecutor from(Workflow workflow) {
        return new ArgoWorkflowExecutor(workflow);
    }

    // -------------------------------------------------------------------------
    // Configuration — call before execute()
    // -------------------------------------------------------------------------

    /**
     * Sets the Kubernetes namespace used for ConfigMap and Secret lookups.
     * Defaults to {@code "default"} when not called.
     *
     * @param namespace the namespace to use for Kubernetes API lookups
     * @return this executor, for chaining
     */
    public ArgoWorkflowExecutor withNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    /**
     * Supplies an externally-managed kwok container.
     *
     * <p>The executor borrows kwok's Docker network and kubeconfig so that step containers
     * can reach the API server, but it does <em>not</em> start or stop the container —
     * the caller owns the lifecycle and must start kwok before calling this method.
     *
     * <p>Use this to share a single kwok instance across multiple executors or test methods,
     * avoiding the startup overhead of a fresh cluster per workflow. Contrast with
     * {@link #getKubernetesClient()}, which starts an executor-owned kwok that is stopped
     * when {@link #close()} is called.
     *
     * <p>If {@link #withNetwork(Network)} is also called, it must reference the same Docker
     * network as {@code kwok.network()}.
     *
     * @param kwok a started {@link KwokContainer} managed by the caller
     * @return this executor, for chaining
     */
    public ArgoWorkflowExecutor withKwok(KwokContainer kwok) {
        this.k8sClient = kwok.createClient();
        this.network = kwok.network();
        this.podKubeconfig = kwok.podKubeconfig();
        return this;
    }

    /**
     * Supplies a Docker network that all step containers will join.
     *
     * <p>Use this when step containers need to communicate with each other or with other
     * containers on a known network, but you are not using kwok. If kwok is also configured
     * (via {@link #withKwok} or {@link #getKubernetesClient}), pass kwok's network here —
     * using a different network will prevent step containers from reaching the API server.
     *
     * <p>The supplied network is not closed by {@link #close()}.
     *
     * @param network a Testcontainers {@link Network} to join
     * @return this executor, for chaining
     */
    public ArgoWorkflowExecutor withNetwork(Network network) {
        this.network = network;
        return this;
    }

    /**
     * Lazily starts a kwok cluster owned by this executor and returns a fabric8
     * {@link KubernetesClient} connected to it.
     *
     * <p>Call this before {@link #execute()} when the workflow uses {@code configMapKeyRef},
     * {@code secretKeyRef}, or other Kubernetes API lookups. The client, network, and
     * kubeconfig are wired into every workflow execution automatically.
     *
     * <p>The kwok container is started once and reused across multiple {@link #execute()} calls
     * on this executor. It is stopped when {@link #close()} is called.
     *
     * <p>To share a single kwok instance across multiple executors or test methods, start
     * kwok externally and use {@link #withKwok(KwokContainer)} instead.
     *
     * @return a {@link KubernetesClient} pointed at the embedded kwok cluster
     */
    public synchronized KubernetesClient getKubernetesClient() {
        if (k8sClient == null) {
            ownedKwok = new KwokContainer();
            ownedKwok.start();
            k8sClient = ownedKwok.createClient();
            network = ownedKwok.network();
            podKubeconfig = ownedKwok.podKubeconfig();
        }
        return k8sClient;
    }

    // -------------------------------------------------------------------------
    // Execution
    // -------------------------------------------------------------------------

    /**
     * Starts the workflow asynchronously and returns a {@link WorkflowRun} immediately.
     *
     * <p>The returned {@link WorkflowRun} tracks the execution tree. Call
     * {@link WorkflowRun#await()} (or use {@link #execute()} directly) to block until
     * the workflow completes. Close the {@link WorkflowRun} to release its temp directory.
     *
     * @return a handle to the running workflow
     */
    public WorkflowRun executeAsync() {
        String entrypointName = workflow.getSpec().getEntrypoint();
        log.debug("Entrypoint: {}", entrypointName);

        Map<String, String> workflowParams = new LinkedHashMap<>();
        if (workflow.getSpec().getArguments() != null &&
                workflow.getSpec().getArguments().getParameters() != null) {
            for (Parameter p : workflow.getSpec().getArguments().getParameters()) {
                if (p.getValue() != null) {
                    workflowParams.put(p.getName(), p.getValue());
                } else if (p.getValueFrom() != null && p.getValueFrom().getConfigMapKeyRef() != null) {
                    // secretKeyRef is intentionally absent: Argo's Parameter.valueFrom does not
                    // support it — secretKeyRef is only valid on container env entries (k8s feature).
                    var ref = p.getValueFrom().getConfigMapKeyRef();
                    workflowParams.put(p.getName(), resolveWorkflowConfigMapKey(ref.getName(), ref.getKey()));
                }
            }
        }

        Map<String, Template> templateMap = new LinkedHashMap<>();
        for (Template t : workflow.getSpec().getTemplates()) {
            templateMap.put(t.getName(), t);
        }
        log.debug("Templates: {}", templateMap.keySet());

        if (k8sClient != null) {
            templateMap.putAll(resolveTemplateRefs(Collections.unmodifiableMap(templateMap), entrypointName));
        }

        Template entrypointTemplate = templateMap.get(entrypointName);

        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "argo-executor");
            t.setDaemon(true);
            return t;
        });

        List<ArtifactDriver> drivers = ServiceLoader.load(ArtifactDriver.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .collect(Collectors.toList());

        Map<String, IoK8sApiCoreV1Volume> volumes = new LinkedHashMap<>();
        if (workflow.getSpec().getVolumes() != null) {
            for (IoK8sApiCoreV1Volume v : workflow.getSpec().getVolumes()) {
                volumes.put(v.getName(), v);
            }
        }

        RetryStrategy defaultRetryStrategy = null;
        if (workflow.getSpec().getTemplateDefaults() != null) {
            defaultRetryStrategy = workflow.getSpec().getTemplateDefaults().getRetryStrategy();
        }

        ExecutionContext ctx = ExecutionContext.builder(templateMap, workflowParams, threadPool)
                .k8sClient(k8sClient)
                .dockerNetwork(resolveNetwork())
                .podKubeconfig(podKubeconfig)
                .namespace(namespace)
                .artifactDrivers(drivers)
                .volumes(volumes)
                .defaultRetryStrategy(defaultRetryStrategy)
                .build();

        // Download workflow-level HTTP input artifacts before execution starts
        Map<String, Path> workflowArtifacts = downloadWorkflowArtifacts(ctx.tmpDir);
        ExecutionContext rootCtx = workflowArtifacts.isEmpty()
                ? ctx : ctx.withInputArtifacts(workflowArtifacts);

        WorkflowNode root = WorkflowNode.from(entrypointName, entrypointTemplate, templateMap, Set.of());
        // Workflow arguments are the initial input parameters for the entrypoint, matching Argo semantics:
        // a template's {{inputs.parameters.X}} at the entrypoint level resolves from workflow arguments.
        CompletableFuture<Void> future = root.executeAsync(rootCtx, workflowParams)
                .thenAccept(_ -> {})
                .whenComplete((_, _) -> threadPool.shutdown());

        return new WorkflowRun(root, future, ctx.tmpDir);
    }

    /**
     * Executes the workflow and blocks until it completes.
     *
     * <p>Equivalent to {@code executeAsync().await()}.
     *
     * @return the completed {@link WorkflowRun}
     * @throws Exception if the workflow execution throws an unexpected error
     */
    public WorkflowRun execute() throws Exception {
        return executeAsync().await();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Releases resources owned by this executor.
     *
     * <p>Stops the kwok container if one was started via {@link #getKubernetesClient()}, and
     * closes the Docker network if one was created automatically. Resources supplied
     * externally via {@link #withKwok} or {@link #withNetwork} are not touched.
     *
     * <p>Safe to call multiple times; subsequent calls are no-ops.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (ownedKwok != null) {
            try { ownedKwok.stop(); } catch (Exception e) { log.warn("Failed to stop kwok", e); }
        }
        if (ownedNetwork != null) {
            try { ownedNetwork.close(); } catch (Exception e) { log.warn("Failed to close network", e); }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the active Docker network, creating and owning a new one if none was
     * supplied via {@link #withKwok}, {@link #withNetwork}, or {@link #getKubernetesClient}.
     */
    private synchronized Network resolveNetwork() {
        if (network == null) {
            ownedNetwork = Network.newNetwork();
            network = ownedNetwork;
        }
        return network;
    }

    /**
     * Fetches all WorkflowTemplates transitively reachable from {@code entrypointName} and
     * returns a map of {@code "wtName/templateName"} entries ready to merge into the local
     * template map.
     *
     * <p>Only templates reachable via the actual call graph are resolved; templateRefs that
     * exist in installed WorkflowTemplates but are never reached from the entrypoint are
     * silently ignored, so tests do not need to install every transitively-mentioned WT.
     */
    private Map<String, Template> resolveTemplateRefs(Map<String, Template> workflowTemplates,
                                                       String entrypointName) {
        Map<String, Template> result = new LinkedHashMap<>();
        // WT name -> { templateName -> Template } (all templates in a fetched WT, cached)
        Map<String, Map<String, Template>> loadedWTs = new LinkedHashMap<>();
        Set<String> visitedLocal = new LinkedHashSet<>();   // workflow-local template names
        Set<String> visitedWTKeys = new LinkedHashSet<>();  // "wtName/templateName" composite keys
        Queue<String> localQueue = new ArrayDeque<>();
        Queue<String> wtKeyQueue = new ArrayDeque<>();

        visitedLocal.add(entrypointName);
        localQueue.add(entrypointName);

        while (!localQueue.isEmpty() || !wtKeyQueue.isEmpty()) {
            while (!localQueue.isEmpty()) {
                String localName = localQueue.poll();
                Template t = workflowTemplates.get(localName);
                if (t != null) scanReachableRefs(t, null, loadedWTs, visitedLocal, visitedWTKeys,
                                                 localQueue, wtKeyQueue, result);
            }
            while (!wtKeyQueue.isEmpty()) {
                String key = wtKeyQueue.poll();
                int slash = key.indexOf('/');
                String wtName = key.substring(0, slash);
                String tmplName = key.substring(slash + 1);
                Template t = loadedWTs.getOrDefault(wtName, Map.of()).get(tmplName);
                if (t != null) scanReachableRefs(t, wtName, loadedWTs, visitedLocal, visitedWTKeys,
                                                 localQueue, wtKeyQueue, result);
            }
        }
        return result;
    }

    private void scanReachableRefs(Template t, String owningWt,
                                    Map<String, Map<String, Template>> loadedWTs,
                                    Set<String> visitedLocal, Set<String> visitedWTKeys,
                                    Queue<String> localQueue, Queue<String> wtKeyQueue,
                                    Map<String, Template> result) {
        if (t.getSteps() != null) {
            for (List<WorkflowStep> group : t.getSteps()) {
                for (WorkflowStep step : group) {
                    scheduleRef(step.getTemplate(), step.getTemplateRef(), owningWt,
                                loadedWTs, visitedLocal, visitedWTKeys, localQueue, wtKeyQueue, result);
                }
            }
        }
        if (t.getDag() != null && t.getDag().getTasks() != null) {
            for (DAGTask task : t.getDag().getTasks()) {
                scheduleRef(task.getTemplate(), task.getTemplateRef(), owningWt,
                            loadedWTs, visitedLocal, visitedWTKeys, localQueue, wtKeyQueue, result);
            }
        }
    }

    private void scheduleRef(String inlineName, TemplateRef templateRef, String owningWt,
                              Map<String, Map<String, Template>> loadedWTs,
                              Set<String> visitedLocal, Set<String> visitedWTKeys,
                              Queue<String> localQueue, Queue<String> wtKeyQueue,
                              Map<String, Template> result) {
        if (inlineName != null) {
            if (owningWt != null) {
                // Sibling reference within a WorkflowTemplate — resolve under the same WT
                scheduleWTKey(owningWt, inlineName, loadedWTs, visitedWTKeys, wtKeyQueue, result);
            } else {
                if (visitedLocal.add(inlineName)) localQueue.add(inlineName);
            }
        }
        if (templateRef != null) {
            scheduleWTKey(templateRef.getName(), templateRef.getTemplate(),
                          loadedWTs, visitedWTKeys, wtKeyQueue, result);
        }
    }

    private void scheduleWTKey(String wtName, String tmplName,
                                Map<String, Map<String, Template>> loadedWTs,
                                Set<String> visitedWTKeys,
                                Queue<String> wtKeyQueue,
                                Map<String, Template> result) {
        String key = wtName + "/" + tmplName;
        if (!visitedWTKeys.add(key)) return;

        if (!loadedWTs.containsKey(wtName)) {
            log.debug("Fetching WorkflowTemplate '{}'", wtName);
            GenericKubernetesResource wt = k8sClient
                    .genericKubernetesResources(WORKFLOW_TEMPLATE_CTX)
                    .inNamespace(namespace).withName(wtName).get();
            if (wt == null) throw new IllegalStateException(
                    "templateRef requires WorkflowTemplate '" + wtName + "' but it was not found"
                    + " in namespace '" + namespace + "'");
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) wt.getAdditionalProperties().get("spec");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawTemplates =
                    spec == null ? null : (List<Map<String, Object>>) spec.get("templates");
            Map<String, Template> wtTemplates = new LinkedHashMap<>();
            if (rawTemplates != null) {
                for (Map<String, Object> raw : rawTemplates) {
                    Template tmpl = JSON.convertValue(raw, Template.class);
                    wtTemplates.put(tmpl.getName(), tmpl);
                }
            }
            loadedWTs.put(wtName, wtTemplates);
        }

        Template t = loadedWTs.get(wtName).get(tmplName);
        if (t != null) {
            result.put(key, t);
            wtKeyQueue.add(key);
        }
    }

    private String resolveWorkflowConfigMapKey(String configMapName, String key) {
        if (k8sClient == null) throw new IllegalStateException(
                "workflow.arguments.parameters[].valueFrom.configMapKeyRef requires a Kubernetes client"
                + " — call getKubernetesClient() or withKwok() before execute()");
        var cm = k8sClient.configMaps().inNamespace(namespace).withName(configMapName).get();
        if (cm == null) throw new IllegalStateException(
                "ConfigMap '" + configMapName + "' not found in namespace '" + namespace + "'");
        var data = cm.getData();
        if (data == null || !data.containsKey(key)) throw new IllegalStateException(
                "Key '" + key + "' not found in ConfigMap '" + configMapName + "'");
        return data.get(key);
    }

    private Map<String, Path> downloadWorkflowArtifacts(Path tmpDir) {
        if (workflow.getSpec().getArguments() == null
                || workflow.getSpec().getArguments().getArtifacts() == null) {
            return Map.of();
        }
        Map<String, Path> downloaded = new LinkedHashMap<>();
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
        for (Artifact a : workflow.getSpec().getArguments().getArtifacts()) {
            if (a.getHttp() != null && a.getHttp().getUrl() != null) {
                String url = a.getHttp().getUrl();
                log.debug("Downloading HTTP artifact '{}' from {}", a.getName(), url);
                try {
                    Path dest = Files.createTempFile(tmpDir, "artifact-" + a.getName() + "-", "");
                    HttpResponse<Path> resp = http.send(
                            HttpRequest.newBuilder(URI.create(url)).build(),
                            HttpResponse.BodyHandlers.ofFile(dest));
                    if (resp.statusCode() / 100 != 2) {
                        throw new IOException("HTTP " + resp.statusCode() + " downloading " + url);
                    }
                    log.debug("Downloaded artifact '{}': {} bytes", a.getName(), Files.size(dest));
                    downloaded.put(a.getName(), dest);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Failed to download HTTP artifact '" + a.getName() + "' from " + url, e);
                }
            }
        }
        return downloaded;
    }
}
