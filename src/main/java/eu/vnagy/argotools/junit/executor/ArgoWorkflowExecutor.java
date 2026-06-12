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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.function.Function;
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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

    private static final String NAME_SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random RANDOM = new Random();

    // YAMLAnchorReplayingFactory (added in Jackson 2.19) replays anchor node tokens on alias
    // references, fixing both POJO-field aliases and string-field aliases transparently.
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLAnchorReplayingFactory())
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
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

    // Docker network for step-container connectivity — null unless kwok is in play
    private Network network;

    // nullable — when set, used instead of new GenericContainer<>(image) to create step containers
    private Function<DockerImageName, GenericContainer<?>> containerFactory = null;

    // Resources owned by this executor; closed by close(), null when externally supplied
    private KwokContainer ownedKwok;

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
     * Overrides how step containers are created.
     *
     * <p><b>Package-private — not part of the public library API.</b> Intended for tests that need
     * to verify behaviour when the JVM and the Docker daemon do not share a filesystem (e.g. DinD).
     *
     * @param factory a function that maps an image name to a configured {@link GenericContainer}
     * @return this executor, for chaining
     */
    ArgoWorkflowExecutor withContainerFactory(Function<DockerImageName, GenericContainer<?>> factory) {
        this.containerFactory = factory;
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
            List<String> roots = new ArrayList<>(List.of(entrypointName));
            String onExitForRefs = workflow.getSpec().getOnExit();
            if (onExitForRefs != null) roots.add(onExitForRefs);
            templateMap.putAll(resolveTemplateRefs(Collections.unmodifiableMap(templateMap), roots));
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
        if (workflow.getSpec().getTemplateDefaults() != null)
            defaultRetryStrategy = workflow.getSpec().getTemplateDefaults().getRetryStrategy();
        if (defaultRetryStrategy == null)
            defaultRetryStrategy = workflow.getSpec().getRetryStrategy();

        var meta = workflow.getMetadata();
        String workflowName = null;
        if (meta != null && meta.getName() != null) {
            workflowName = meta.getName();
        } else if (meta != null && meta.getGenerateName() != null) {
            workflowName = meta.getGenerateName() + generateWorkflowSuffix();
        }
        Instant workflowStart = Instant.now();
        String creationTimestamp = DateTimeFormatter.ISO_INSTANT.format(workflowStart);

        ExecutionContext ctx = ExecutionContext.builder(templateMap, workflowParams, threadPool)
                .k8sClient(k8sClient)
                .dockerNetwork(network)
                .podKubeconfig(podKubeconfig)
                .namespace(namespace)
                .artifactDrivers(drivers)
                .volumes(volumes)
                .defaultRetryStrategy(defaultRetryStrategy)
                .containerFactory(containerFactory)
                .workflowName(workflowName)
                .workflowCreationTimestamp(creationTimestamp)
                .build();

        // Download workflow-level HTTP input artifacts before execution starts
        Map<String, Path> workflowArtifacts = downloadWorkflowArtifacts(ctx.tmpDir);
        ExecutionContext rootCtx = workflowArtifacts.isEmpty()
                ? ctx : ctx.withInputArtifacts(workflowArtifacts);

        WorkflowNode root = WorkflowNode.from(entrypointName, entrypointTemplate, templateMap, Set.of());

        String onExitName = workflow.getSpec().getOnExit();
        WorkflowNode exitHandlerNode = null;
        CompletableFuture<Void> future;

        if (onExitName != null) {
            Template exitTemplate = templateMap.get(onExitName);
            if (exitTemplate == null) throw new IllegalArgumentException(
                    "onExit template '" + onExitName + "' not found in workflow");
            WorkflowNode exitHandler = WorkflowNode.from(onExitName, exitTemplate, templateMap, Set.of());
            exitHandlerNode = exitHandler;
            // Workflow arguments are the initial input parameters for the entrypoint, matching Argo semantics:
            // a template's {{inputs.parameters.X}} at the entrypoint level resolves from workflow arguments.
            future = root.executeAsync(rootCtx, workflowParams)
                    .thenCompose(entrypointResult -> {
                        String status = root.succeeded() ? "Succeeded"
                                : root.failed() ? "Failed" : "Error";
                        long durationSeconds = ChronoUnit.SECONDS.between(workflowStart, Instant.now());
                        log.debug("Entrypoint finished with status '{}'; running onExit handler '{}'",
                                status, onExitName);
                        return exitHandler.executeAsync(
                                rootCtx.withWorkflowStatus(status, durationSeconds),
                                workflowParams)
                                .thenAccept(_ -> {});
                    })
                    .whenComplete((_, _) -> threadPool.shutdown());
        } else {
            // Workflow arguments are the initial input parameters for the entrypoint, matching Argo semantics:
            // a template's {{inputs.parameters.X}} at the entrypoint level resolves from workflow arguments.
            future = root.executeAsync(rootCtx, workflowParams)
                    .thenAccept(_ -> {})
                    .whenComplete((_, _) -> threadPool.shutdown());
        }

        return new WorkflowRun(root, exitHandlerNode, future, ctx.tmpDir, rootCtx.globalOutputParams);
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

    /**
     * Executes the workflow and blocks until it completes or {@code timeout} elapses.
     *
     * <p>Equivalent to {@code executeAsync().await(timeout)}.
     *
     * @param timeout maximum time to wait for the workflow to finish
     * @return the completed {@link WorkflowRun}
     * @throws AssertionError if the workflow does not finish within {@code timeout}
     * @throws Exception if the workflow execution throws an unexpected error
     */
    public WorkflowRun execute(Duration timeout) throws Exception {
        return executeAsync().await(timeout);
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
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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
                                                       Collection<String> roots) {
        Map<String, Template> result = new LinkedHashMap<>();
        // WT name -> { templateName -> Template } (all templates in a fetched WT, cached)
        Map<String, Map<String, Template>> loadedWTs = new LinkedHashMap<>();
        Set<String> visitedLocal = new LinkedHashSet<>();   // workflow-local template names
        Set<String> visitedWTKeys = new LinkedHashSet<>();  // "wtName/templateName" composite keys
        Queue<String> localQueue = new ArrayDeque<>();
        Queue<String> wtKeyQueue = new ArrayDeque<>();

        for (String root : roots) {
            visitedLocal.add(root);
            localQueue.add(root);
        }

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

    private static String generateWorkflowSuffix() {
        char[] chars = new char[5];
        for (int i = 0; i < 5; i++) {
            chars[i] = NAME_SUFFIX_CHARS.charAt(RANDOM.nextInt(NAME_SUFFIX_CHARS.length()));
        }
        return new String(chars);
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
            if (a.getRaw() != null && a.getRaw().getData() != null) {
                try {
                    Path dest = Files.createTempFile(tmpDir, "artifact-" + a.getName() + "-", "");
                    Files.writeString(dest, a.getRaw().getData());
                    downloaded.put(a.getName(), dest);
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Failed to materialize raw artifact '" + a.getName() + "'", e);
                }
            } else if (a.getHttp() != null && a.getHttp().getUrl() != null) {
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
