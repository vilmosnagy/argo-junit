package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.artifact.ArtifactDriver;
import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1Volume;
import eu.vnagy.argotools.junit.model.RetryStrategy;
import eu.vnagy.argotools.junit.model.Template;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(ExecutionContext.class);

    private static final Pattern STEP_OUTPUT_RESULT =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.outputs\\.result\\}\\}");
    private static final Pattern STEP_IP =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.ip\\}\\}");
    private static final Pattern TASK_IP =
            Pattern.compile("\\{\\{tasks\\.([^.}]+)\\.ip\\}\\}");
    private static final Pattern STEP_OUTPUT_PARAM =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.outputs\\.parameters\\.([^}]+)\\}\\}");
    private static final Pattern TASK_OUTPUT_PARAM =
            Pattern.compile("\\{\\{tasks\\.([^.}]+)\\.outputs\\.parameters\\.([^}]+)\\}\\}");
    private static final Pattern INPUTS_PARAMETER =
            Pattern.compile("\\{\\{inputs\\.parameters\\.([^}]+)\\}\\}");
    private static final Pattern WORKFLOW_PARAMETER =
            Pattern.compile("\\{\\{workflow\\.parameters\\.([^}]+)\\}\\}");
    // package-private so StepsRun/DagRun can parse from: expressions at plan time
    static final Pattern STEP_ARTIFACT_FROM =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.outputs\\.artifacts\\.([^}]+)\\}\\}");
    static final Pattern TASK_ARTIFACT_FROM =
            Pattern.compile("\\{\\{tasks\\.([^.}]+)\\.outputs\\.artifacts\\.([^}]+)\\}\\}");

    final Map<String, Template> templateMap;
    final Map<String, String> workflowParams;
    final ExecutorService threadPool;
    final ConcurrentHashMap<String, String> stepOutputResults;
    final ConcurrentHashMap<String, String> stepIps;
    final ConcurrentHashMap<String, String> taskIps;
    final ConcurrentHashMap<String, Map<String, Path>> stepArtifacts;
    final ConcurrentHashMap<String, Map<String, Path>> taskArtifacts;
    // paramName -> value, registered after each step/task completes (from outputs.parameters[].valueFrom.path)
    final ConcurrentHashMap<String, Map<String, String>> stepOutputParams;
    final ConcurrentHashMap<String, Map<String, String>> taskOutputParams;
    // resolved input artifact paths for the current pod invocation (immutable per-pod)
    final Map<String, Path> inputArtifacts;
    // single root temp directory for all artifact files created during this run
    final Path tmpDir;
    // artifact names this pod should collect; null means collect all declared outputs
    final Set<String> requestedOutputArtifacts;
    // nullable — only set when getKubernetesClient() was called before execute()
    final KubernetesClient k8sClient;
    // nullable — Docker network that step containers join to reach kwok
    final Network dockerNetwork;
    // nullable — kubeconfig content injected into every step container when kwok is running
    final String podKubeconfig;
    // Kubernetes namespace used for ConfigMap lookups; defaults to "default"
    final String namespace;
    // drivers for explicit artifact locations (s3:, gcs:, azure:); discovered via ServiceLoader
    final List<ArtifactDriver> artifactDrivers;
    // volumeName -> volume spec for all volumes declared in the workflow spec
    final Map<String, IoK8sApiCoreV1Volume> volumes;
    // nullable — retryStrategy from spec.templateDefaults, used when a template has no own retryStrategy
    final RetryStrategy defaultRetryStrategy;

    private ExecutionContext(Builder b) {
        this.templateMap = b.templateMap;
        this.workflowParams = b.workflowParams;
        this.threadPool = b.threadPool;
        this.stepOutputResults = b.stepOutputResults;
        this.stepIps = b.stepIps;
        this.taskIps = b.taskIps;
        this.stepArtifacts = b.stepArtifacts;
        this.taskArtifacts = b.taskArtifacts;
        this.stepOutputParams = b.stepOutputParams;
        this.taskOutputParams = b.taskOutputParams;
        this.inputArtifacts = b.inputArtifacts;
        this.tmpDir = b.tmpDir != null ? b.tmpDir : createTmpDir();
        this.requestedOutputArtifacts = b.requestedOutputArtifacts;
        this.k8sClient = b.k8sClient;
        this.dockerNetwork = b.dockerNetwork;
        this.podKubeconfig = b.podKubeconfig;
        this.namespace = b.namespace;
        this.artifactDrivers = b.artifactDrivers;
        this.volumes = b.volumes;
        this.defaultRetryStrategy = b.defaultRetryStrategy;
    }

    static Builder builder(Map<String, Template> templateMap, Map<String, String> workflowParams,
                           ExecutorService threadPool) {
        return new Builder(templateMap, workflowParams, threadPool);
    }

    /** Returns a builder pre-populated with all fields from this context (scope maps by reference). */
    Builder toBuilder() {
        Builder b = new Builder(templateMap, workflowParams, threadPool);
        b.stepOutputResults = stepOutputResults;
        b.stepIps = stepIps;
        b.taskIps = taskIps;
        b.stepArtifacts = stepArtifacts;
        b.taskArtifacts = taskArtifacts;
        b.stepOutputParams = stepOutputParams;
        b.taskOutputParams = taskOutputParams;
        b.inputArtifacts = inputArtifacts;
        b.tmpDir = tmpDir;
        b.requestedOutputArtifacts = requestedOutputArtifacts;
        b.k8sClient = k8sClient;
        b.dockerNetwork = dockerNetwork;
        b.podKubeconfig = podKubeconfig;
        b.namespace = namespace;
        b.artifactDrivers = artifactDrivers;
        b.volumes = volumes;
        b.defaultRetryStrategy = defaultRetryStrategy;
        return b;
    }

    /** Fresh scope for a sub-workflow execution — shares infrastructure, creates new scope maps. */
    ExecutionContext childScope() {
        return builder(templateMap, workflowParams, threadPool)
                .k8sClient(k8sClient)
                .dockerNetwork(dockerNetwork)
                .podKubeconfig(podKubeconfig)
                .namespace(namespace)
                .artifactDrivers(artifactDrivers)
                .tmpDir(tmpDir)
                .volumes(volumes)
                .defaultRetryStrategy(defaultRetryStrategy)
                .build();
    }

    /**
     * Returns a view of this context with the given per-pod input artifacts injected.
     * Shares all scope maps with the parent (so outputs registered here are visible to the parent).
     */
    ExecutionContext withInputArtifacts(Map<String, Path> artifacts) {
        return toBuilder()
                .inputArtifacts(artifacts)
                .build();
    }

    /** Returns a view of this context specifying which output artifact names the pod should collect. */
    ExecutionContext withRequestedOutputArtifacts(Set<String> names) {
        return toBuilder()
                .requestedOutputArtifacts(names)
                .build();
    }

    /** Returns the first driver that handles the given artifact's location type, if any. */
    Optional<ArtifactDriver> findDriver(Artifact artifact) {
        return artifactDrivers.stream().filter(d -> d.supports(artifact)).findFirst();
    }

    /**
     * Resolves a {@code from:} expression like
     * {@code {{steps.X.outputs.artifacts.Y}}} or {@code {{tasks.X.outputs.artifacts.Y}}}
     * to the host-side temp file path where the artifact was collected.
     */
    Optional<Path> resolveArtifactFrom(String from) {
        from = from.trim();
        Matcher m = STEP_ARTIFACT_FROM.matcher(from);
        if (m.matches()) {
            Map<String, Path> arts = stepArtifacts.get(m.group(1));
            return arts != null ? Optional.ofNullable(arts.get(m.group(2))) : Optional.empty();
        }
        m = TASK_ARTIFACT_FROM.matcher(from);
        if (m.matches()) {
            Map<String, Path> arts = taskArtifacts.get(m.group(1));
            return arts != null ? Optional.ofNullable(arts.get(m.group(2))) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Resolves a ConfigMap key from the Kubernetes API (kwok).
     * Fails fast with a clear error if no client was provided.
     */
    String resolveConfigMapKey(String namespace, String configMapName, String key) {
        if (k8sClient == null) throw new IllegalStateException(
                "configMapKeyRef requires a Kubernetes client — call"
                + " ArgoWorkflowExecutor.getKubernetesClient() before execute()");
        var cm = k8sClient.configMaps().inNamespace(namespace).withName(configMapName).get();
        if (cm == null) throw new IllegalStateException(
                "ConfigMap '" + configMapName + "' not found in namespace '" + namespace + "'");
        var data = cm.getData();
        if (data == null || !data.containsKey(key)) throw new IllegalStateException(
                "Key '" + key + "' not found in ConfigMap '" + configMapName + "'");
        return data.get(key);
    }

    /**
     * Resolves a Secret key from the Kubernetes API (kwok).
     * Secret data values are base64-encoded in the API; this method decodes them.
     * Fails fast with a clear error if no client was provided.
     */
    String resolveSecretKey(String namespace, String secretName, String key) {
        if (k8sClient == null) throw new IllegalStateException(
                "secretKeyRef requires a Kubernetes client — call"
                + " ArgoWorkflowExecutor.getKubernetesClient() before execute()");
        var secret = k8sClient.secrets().inNamespace(namespace).withName(secretName).get();
        if (secret == null) throw new IllegalStateException(
                "Secret '" + secretName + "' not found in namespace '" + namespace + "'");
        var data = secret.getData();
        if (data == null || !data.containsKey(key)) throw new IllegalStateException(
                "Key '" + key + "' not found in Secret '" + secretName + "'");
        return new String(java.util.Base64.getDecoder().decode(data.get(key)));
    }

    String substitute(String expr, Map<String, String> inputParams) {
        String result = applyPattern(expr, STEP_OUTPUT_RESULT, stepOutputResults);
        result = applyPattern(result, STEP_IP, stepIps);
        result = applyPattern(result, TASK_IP, taskIps);
        result = applyNestedPattern(result, STEP_OUTPUT_PARAM, stepOutputParams);
        result = applyNestedPattern(result, TASK_OUTPUT_PARAM, taskOutputParams);
        result = applyPattern(result, INPUTS_PARAMETER, inputParams);
        result = applyPattern(result, WORKFLOW_PARAMETER, workflowParams);
        if (!result.equals(expr)) {
            log.trace("Substitute: '{}' → '{}'", expr, result);
        }
        return result;
    }

    List<String> substituteAll(List<String> strings, Map<String, String> inputParams) {
        return strings.stream()
                .map(s -> substitute(s, inputParams))
                .collect(Collectors.toList());
    }

    boolean evaluateWhen(String condition) {
        condition = condition.trim();
        if (condition.contains(" == ")) {
            String[] parts = condition.split(" == ", 2);
            return parts[0].trim().equals(parts[1].trim());
        }
        if (condition.contains(" != ")) {
            String[] parts = condition.split(" != ", 2);
            return !parts[0].trim().equals(parts[1].trim());
        }
        return Boolean.parseBoolean(condition);
    }

    private String applyPattern(String expr, Pattern pattern, Map<String, String> values) {
        Matcher m = pattern.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = values.getOrDefault(key, m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyNestedPattern(String expr, Pattern pattern,
                                      Map<String, Map<String, String>> values) {
        Matcher m = pattern.matcher(expr);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Map<String, String> inner = values.get(m.group(1));
            String value = inner != null ? inner.getOrDefault(m.group(2), m.group(0)) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Path createTmpDir() {
        try {
            return Files.createTempDirectory("argo-run-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -------------------------------------------------------------------------

    static final class Builder {

        private final Map<String, Template> templateMap;
        private final Map<String, String> workflowParams;
        private final ExecutorService threadPool;

        // Scope maps — fresh instances by default; toBuilder() replaces these with existing refs
        private ConcurrentHashMap<String, String> stepOutputResults = new ConcurrentHashMap<>();
        private ConcurrentHashMap<String, String> stepIps = new ConcurrentHashMap<>();
        private ConcurrentHashMap<String, String> taskIps = new ConcurrentHashMap<>();
        private ConcurrentHashMap<String, Map<String, Path>> stepArtifacts = new ConcurrentHashMap<>();
        private ConcurrentHashMap<String, Map<String, Path>> taskArtifacts = new ConcurrentHashMap<>();
        private ConcurrentHashMap<String, Map<String, String>> stepOutputParams = new ConcurrentHashMap<>();
        private ConcurrentHashMap<String, Map<String, String>> taskOutputParams = new ConcurrentHashMap<>();

        // Per-pod fields
        private Map<String, Path> inputArtifacts = Map.of();
        private Set<String> requestedOutputArtifacts = null;

        // Infrastructure — null/default until explicitly set
        private Path tmpDir = null;          // null → createTmpDir() at build() time
        private KubernetesClient k8sClient = null;
        private Network dockerNetwork = null;
        private String podKubeconfig = null;
        private String namespace = "default";
        private List<ArtifactDriver> artifactDrivers = List.of();
        private Map<String, IoK8sApiCoreV1Volume> volumes = Map.of();
        private RetryStrategy defaultRetryStrategy = null;

        private Builder(Map<String, Template> templateMap, Map<String, String> workflowParams,
                        ExecutorService threadPool) {
            this.templateMap = templateMap;
            this.workflowParams = workflowParams;
            this.threadPool = threadPool;
        }

        Builder k8sClient(KubernetesClient v)       { this.k8sClient = v; return this; }
        Builder dockerNetwork(Network v)             { this.dockerNetwork = v; return this; }
        Builder podKubeconfig(String v)              { this.podKubeconfig = v; return this; }
        Builder namespace(String v)                  { this.namespace = v; return this; }
        Builder tmpDir(Path v)                       { this.tmpDir = v; return this; }
        Builder inputArtifacts(Map<String, Path> v)  { this.inputArtifacts = Map.copyOf(v); return this; }
        Builder requestedOutputArtifacts(Set<String> v) {
            this.requestedOutputArtifacts = v != null ? Set.copyOf(v) : null;
            return this;
        }
        Builder artifactDrivers(List<ArtifactDriver> v) {
            this.artifactDrivers = List.copyOf(v);
            return this;
        }
        Builder volumes(Map<String, IoK8sApiCoreV1Volume> v) {
            this.volumes = Map.copyOf(v);
            return this;
        }
        Builder defaultRetryStrategy(RetryStrategy v) { this.defaultRetryStrategy = v; return this; }

        ExecutionContext build() { return new ExecutionContext(this); }
    }
}
