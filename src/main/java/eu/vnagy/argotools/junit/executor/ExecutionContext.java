package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.artifact.ArtifactDriver;
import eu.vnagy.argotools.junit.model.Artifact;
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
    // artifactName -> hostPath, registered after each step/task completes
    final ConcurrentHashMap<String, Map<String, Path>> stepArtifacts;
    final ConcurrentHashMap<String, Map<String, Path>> taskArtifacts;
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

    ExecutionContext(Map<String, Template> templateMap, Map<String, String> workflowParams,
                    ExecutorService threadPool, KubernetesClient k8sClient,
                    Network dockerNetwork, String podKubeconfig, String namespace,
                    List<ArtifactDriver> artifactDrivers) {
        this(templateMap, workflowParams, threadPool,
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Map.of(),
                createTmpDir(), null, k8sClient, dockerNetwork, podKubeconfig, namespace,
                artifactDrivers);
    }

    private static Path createTmpDir() {
        try {
            return Files.createTempDirectory("argo-run-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ExecutionContext(Map<String, Template> templateMap, Map<String, String> workflowParams,
                             ExecutorService threadPool,
                             ConcurrentHashMap<String, String> stepOutputResults,
                             ConcurrentHashMap<String, String> stepIps,
                             ConcurrentHashMap<String, String> taskIps,
                             ConcurrentHashMap<String, Map<String, Path>> stepArtifacts,
                             ConcurrentHashMap<String, Map<String, Path>> taskArtifacts,
                             Map<String, Path> inputArtifacts,
                             Path tmpDir,
                             Set<String> requestedOutputArtifacts,
                             KubernetesClient k8sClient,
                             Network dockerNetwork,
                             String podKubeconfig,
                             String namespace,
                             List<ArtifactDriver> artifactDrivers) {
        this.templateMap = templateMap;
        this.workflowParams = workflowParams;
        this.threadPool = threadPool;
        this.stepOutputResults = stepOutputResults;
        this.stepIps = stepIps;
        this.taskIps = taskIps;
        this.stepArtifacts = stepArtifacts;
        this.taskArtifacts = taskArtifacts;
        this.inputArtifacts = inputArtifacts;
        this.tmpDir = tmpDir;
        this.requestedOutputArtifacts = requestedOutputArtifacts;
        this.k8sClient = k8sClient;
        this.dockerNetwork = dockerNetwork;
        this.podKubeconfig = podKubeconfig;
        this.namespace = namespace;
        this.artifactDrivers = List.copyOf(artifactDrivers);
    }

    /** Fresh scope for a sub-workflow execution — inherits global maps, resets local ones. */
    ExecutionContext childScope() {
        return new ExecutionContext(templateMap, workflowParams, threadPool,
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
                new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Map.of(),
                tmpDir, null, k8sClient, dockerNetwork, podKubeconfig, namespace, artifactDrivers);
    }

    /**
     * Returns a view of this context with the given per-pod input artifacts injected.
     * Shares all scope maps with the parent (so outputs registered here are visible to the parent).
     */
    ExecutionContext withInputArtifacts(Map<String, Path> artifacts) {
        return new ExecutionContext(templateMap, workflowParams, threadPool,
                stepOutputResults, stepIps, taskIps,
                stepArtifacts, taskArtifacts,
                Map.copyOf(artifacts),
                tmpDir, requestedOutputArtifacts, k8sClient, dockerNetwork, podKubeconfig, namespace,
                artifactDrivers);
    }

    /** Returns a view of this context specifying which output artifact names the pod should collect. */
    ExecutionContext withRequestedOutputArtifacts(Set<String> names) {
        return new ExecutionContext(templateMap, workflowParams, threadPool,
                stepOutputResults, stepIps, taskIps,
                stepArtifacts, taskArtifacts,
                inputArtifacts,
                tmpDir, Set.copyOf(names), k8sClient, dockerNetwork, podKubeconfig, namespace,
                artifactDrivers);
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

    String substitute(String expr, Map<String, String> inputParams) {
        String result = applyPattern(expr, STEP_OUTPUT_RESULT, stepOutputResults);
        result = applyPattern(result, STEP_IP, stepIps);
        result = applyPattern(result, TASK_IP, taskIps);
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
}
