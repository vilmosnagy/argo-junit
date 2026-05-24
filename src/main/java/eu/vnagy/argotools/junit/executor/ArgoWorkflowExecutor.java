package eu.vnagy.argotools.junit.executor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.model.Arguments;
import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.Parameter;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ArgoWorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(ArgoWorkflowExecutor.class);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern STEP_OUTPUT_RESULT =
            Pattern.compile("\\{\\{steps\\.([^.}]+)\\.outputs\\.result\\}\\}");
    private static final Pattern INPUTS_PARAMETER =
            Pattern.compile("\\{\\{inputs\\.parameters\\.([^}]+)\\}\\}");
    private static final Pattern WORKFLOW_PARAMETER =
            Pattern.compile("\\{\\{workflow\\.parameters\\.([^}]+)\\}\\}");

    private final Workflow workflow; // Path, String, or Workflow
    private final Map<String, String> workflowParams = new LinkedHashMap<>();

    private ArgoWorkflowExecutor(Workflow workflow) {
        this.workflow = workflow;
    }

    public static ArgoWorkflowExecutor from(Path workflowFile) {
        try {
            return new ArgoWorkflowExecutor(YAML.readValue(Files.newInputStream(workflowFile), Workflow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read workflow file: " + workflowFile, e);
        }
    }

    public static ArgoWorkflowExecutor from(String workflowYaml) {
        try {
            return new ArgoWorkflowExecutor(YAML.readValue(workflowYaml, Workflow.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse workflow YAML", e);
        }
    }

    public static ArgoWorkflowExecutor from(Workflow workflow) {
        return new ArgoWorkflowExecutor(workflow);
    }

    public LiveWorkflowRun executeAsync() throws Exception {
        String entrypointName = workflow.getSpec().getEntrypoint();
        log.debug("Entrypoint: {}", entrypointName);

        // Collect workflow-level arguments into the substitution map
        if (workflow.getSpec().getArguments() != null &&
                workflow.getSpec().getArguments().getParameters() != null) {
            for (Parameter p : workflow.getSpec().getArguments().getParameters()) {
                if (p.getValue() != null) workflowParams.put(p.getName(), p.getValue());
            }
        }

        Map<String, Template> templateMap = new LinkedHashMap<>();
        for (Template t : workflow.getSpec().getTemplates()) {
            templateMap.put(t.getName(), t);
        }
        log.debug("Templates: {}", templateMap.keySet());

        Template entrypointTemplate = templateMap.get(entrypointName);
        ConcurrentHashMap<String, PodRun> podStates = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> stepOutputResults = new ConcurrentHashMap<>();

        ExecutorService threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "argo-executor");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture<WorkflowNode> rootFuture = executeTemplateAsync(
                entrypointName, entrypointTemplate, templateMap,
                stepOutputResults, Map.of(), podStates, threadPool);

        CompletableFuture<WorkflowRun> runFuture = rootFuture
                .thenApply(WorkflowRun::new)
                .whenComplete((_, _) -> threadPool.shutdown());

        return new LiveWorkflowRun(entrypointName, entrypointTemplate, templateMap, podStates, runFuture);
    }

    public WorkflowRun execute() throws Exception {
        return executeAsync().await();
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    private CompletableFuture<WorkflowNode> executeTemplateAsync(
            String nodeName, Template template,
            Map<String, Template> templateMap,
            ConcurrentHashMap<String, String> stepOutputResults,
            Map<String, String> inputParams,
            ConcurrentHashMap<String, PodRun> podStates,
            ExecutorService threadPool) {

        if (template.getDag() != null) {
            log.debug("Template '{}': dispatching as dag", nodeName);
            return executeDagAsync(nodeName, template, templateMap, stepOutputResults, podStates, threadPool);
        }
        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            log.debug("Template '{}': dispatching as steps", nodeName);
            return executeStepsAsync(nodeName, template, templateMap, stepOutputResults, podStates, threadPool);
        }
        if (template.getScript() != null || template.getContainer() != null) {
            log.debug("Template '{}': dispatching as pod", nodeName);
            return runPodAsync(nodeName, template, stepOutputResults, inputParams, podStates, threadPool);
        }
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Unsupported template type: " + nodeName));
    }

    // -------------------------------------------------------------------------
    // DAG
    // -------------------------------------------------------------------------

    private CompletableFuture<WorkflowNode> executeDagAsync(
            String name, Template template,
            Map<String, Template> templateMap,
            ConcurrentHashMap<String, String> stepOutputResults,
            ConcurrentHashMap<String, PodRun> podStates,
            ExecutorService threadPool) {

        List<DAGTask> tasks = template.getDag().getTasks();
        List<DAGTask> sorted = topologicalSort(tasks);
        log.debug("Dag '{}': {} task(s) in topological order: {}", name, sorted.size(),
                sorted.stream().map(DAGTask::getName).collect(Collectors.joining(", ")));

        Map<String, CompletableFuture<WorkflowNode>> futures = new LinkedHashMap<>();

        for (DAGTask task : sorted) {
            List<String> deps = parseDependencies(task.getDepends());
            Map<String, String> inputParams = resolveArgs(task.getArguments());
            Template taskTemplate = templateMap.get(task.getTemplate());

            CompletableFuture<Void> depsReady = deps.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(
                            deps.stream().map(futures::get).toArray(CompletableFuture[]::new));

            log.debug("Dag '{}': task '{}' depends on {}", name, task.getName(), deps);
            CompletableFuture<WorkflowNode> taskFuture = depsReady.thenComposeAsync(
                    _ -> executeTemplateAsync(task.getName(), taskTemplate, templateMap,
                            stepOutputResults, inputParams, podStates, threadPool),
                    threadPool);

            futures.put(task.getName(), taskFuture);
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .thenApply(_ -> {
                    Map<String, WorkflowNode> results = new LinkedHashMap<>();
                    futures.forEach((k, f) -> results.put(k, f.join()));
                    log.debug("Dag '{}': all tasks completed", name);
                    return (WorkflowNode) new DagRun(name, results);
                });
    }

    private List<DAGTask> topologicalSort(List<DAGTask> tasks) {
        Map<String, DAGTask> byName = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (DAGTask task : tasks) {
            byName.put(task.getName(), task);
            dependents.put(task.getName(), new ArrayList<>());
            inDegree.put(task.getName(), 0);
        }
        for (DAGTask task : tasks) {
            for (String dep : parseDependencies(task.getDepends())) {
                dependents.get(dep).add(task.getName());
                inDegree.merge(task.getName(), 1, Integer::sum);
            }
        }

        Queue<String> queue = new ArrayDeque<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey)
                .forEach(queue::add);

        List<DAGTask> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(byName.get(current));
            for (String dep : dependents.get(current)) {
                if (inDegree.merge(dep, -1, Integer::sum) == 0) queue.add(dep);
            }
        }

        if (sorted.size() != tasks.size()) {
            throw new IllegalStateException("Cycle detected in DAG dependencies");
        }
        return sorted;
    }

    private List<String> parseDependencies(String depends) {
        if (depends == null || depends.isBlank()) return List.of();
        return Arrays.stream(depends.split("[^A-Za-z0-9_-]+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Steps
    // -------------------------------------------------------------------------

    private CompletableFuture<WorkflowNode> executeStepsAsync(
            String name, Template template,
            Map<String, Template> templateMap,
            ConcurrentHashMap<String, String> stepOutputResults,
            ConcurrentHashMap<String, PodRun> podStates,
            ExecutorService threadPool) {

        List<List<WorkflowStep>> groups = template.getSteps();
        log.debug("Steps '{}': {} group(s)", name, groups.size());

        CompletableFuture<Map<String, WorkflowNode>> chain =
                CompletableFuture.completedFuture(new LinkedHashMap<>());

        for (int i = 0; i < groups.size(); i++) {
            final List<WorkflowStep> group = List.copyOf(groups.get(i));
            final int groupIndex = i;

            chain = chain.thenComposeAsync(acc -> {
                log.debug("Steps '{}': executing group {} [{} step(s)]", name, groupIndex, group.size());

                Map<String, CompletableFuture<WorkflowNode>> groupFutures = new LinkedHashMap<>();

                for (WorkflowStep step : group) {
                    String when = step.getWhen();
                    if (when != null) {
                        String substituted = substitute(when, stepOutputResults, Map.of());
                        boolean run = evaluateWhen(substituted);
                        log.debug("Step '{}': when='{}' → '{}' → {}", step.getName(), when,
                                substituted, run ? "run" : "skip");
                        if (!run) {
                            PodRun skipped = PodRun.skipped(step.getName());
                            podStates.put(step.getName(), skipped);
                            groupFutures.put(step.getName(), CompletableFuture.completedFuture(skipped));
                            continue;
                        }
                    }

                    log.debug("Step '{}': running template '{}'", step.getName(), step.getTemplate());
                    Template stepTemplate = templateMap.get(step.getTemplate());
                    groupFutures.put(step.getName(),
                            executeTemplateAsync(step.getName(), stepTemplate, templateMap,
                                    stepOutputResults, Map.of(), podStates, threadPool)
                                    .thenApply(node -> {
                                        if (node instanceof PodRun pod) {
                                            pod.outputResult().ifPresent(r -> {
                                                log.debug("Step '{}': outputs.result='{}'", pod.name(), r);
                                                stepOutputResults.put(step.getName(), r);
                                            });
                                        }
                                        return node;
                                    }));
                }

                return CompletableFuture.allOf(groupFutures.values().toArray(new CompletableFuture[0]))
                        .thenApply(_ -> {
                            Map<String, WorkflowNode> result = new LinkedHashMap<>(acc);
                            groupFutures.forEach((k, f) -> result.put(k, f.join()));
                            return result;
                        });
            }, threadPool);
        }

        return chain.thenApply(allSteps -> {
            log.debug("Steps '{}': all groups completed", name);
            return (WorkflowNode) new StepsRun(name, allSteps);
        });
    }

    // -------------------------------------------------------------------------
    // Pod execution
    // -------------------------------------------------------------------------

    private CompletableFuture<WorkflowNode> runPodAsync(
            String name, Template template,
            ConcurrentHashMap<String, String> stepOutputResults,
            Map<String, String> inputParams,
            ConcurrentHashMap<String, PodRun> podStates,
            ExecutorService threadPool) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                return runPod(name, template, stepOutputResults, inputParams, podStates);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, threadPool);
    }

    private PodRun runPod(String name, Template template,
                          ConcurrentHashMap<String, String> stepOutputResults,
                          Map<String, String> inputParams,
                          ConcurrentHashMap<String, PodRun> podStates) throws Exception {
        String image;
        List<String> command;
        String scriptSource = null;

        if (template.getScript() != null) {
            var script = template.getScript();
            image = script.getImage();
            List<String> base = script.getCommand() != null ? script.getCommand() : List.of();
            command = new ArrayList<>(base);
            command.add("/tmp/script");
            scriptSource = substitute(script.getSource(), stepOutputResults, inputParams);
        } else {
            var container = template.getContainer();
            image = container.getImage();
            List<String> cmd = container.getCommand() != null ? container.getCommand() : List.of();
            List<String> args = container.getArgs() != null ? container.getArgs() : List.of();
            command = new ArrayList<>(cmd);
            command.addAll(args);
        }

        image = substitute(image, stepOutputResults, inputParams);
        command = substituteAll(command, stepOutputResults, inputParams);

        log.debug("Pod '{}': starting image='{}' command={}", name, image, command);

        @SuppressWarnings("resource")
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withCommand(command.toArray(String[]::new))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy()
                        .withTimeout(Duration.ofMinutes(10)))
                .waitingFor(new AbstractWaitStrategy() {
                    @Override
                    protected void waitUntilReady() {}
                });

        if (scriptSource != null) {
            log.debug("Pod '{}': copying script source to /tmp/script", name);
            Path scriptFile = Files.createTempFile("argo-script-", "");
            Files.writeString(scriptFile, scriptSource);
            container.withCopyFileToContainer(MountableFile.forHostPath(scriptFile), "/tmp/script");
        }

        podStates.put(name, PodRun.running(name));
        Instant start = Instant.now();
        container.start();
        Duration duration = Duration.between(start, Instant.now());

        int exitCode = container.getCurrentContainerInfo().getState().getExitCodeLong().intValue();
        String logs = container.getLogs();
        String stdout = container.getLogs(OutputFrame.OutputType.STDOUT).trim();

        log.debug("Pod '{}': finished exitCode={} duration={}s", name, exitCode, duration.getSeconds());
        if (template.getScript() != null) {
            log.debug("Pod '{}': stdout='{}'", name, stdout);
        }

        String outputResult = template.getScript() != null ? stdout : null;
        PodRun result = PodRun.completed(name, exitCode, logs, outputResult, container, duration);
        podStates.put(name, result);
        return result;
    }

    // -------------------------------------------------------------------------
    // Expression substitution
    // -------------------------------------------------------------------------

    private Map<String, String> resolveArgs(Arguments arguments) {
        if (arguments == null || arguments.getParameters() == null) return Map.of();
        Map<String, String> params = new LinkedHashMap<>();
        for (Parameter p : arguments.getParameters()) {
            if (p.getValue() != null) params.put(p.getName(), p.getValue());
        }
        return params;
    }

    private String substitute(String expr, Map<String, String> stepOutputResults,
                               Map<String, String> inputParams) {
        String result = applyPattern(expr, STEP_OUTPUT_RESULT, stepOutputResults);
        result = applyPattern(result, INPUTS_PARAMETER, inputParams);
        result = applyPattern(result, WORKFLOW_PARAMETER, workflowParams);
        if (!result.equals(expr)) {
            log.debug("Substitute: '{}' → '{}'", expr, result);
        }
        return result;
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

    private List<String> substituteAll(List<String> strings, Map<String, String> stepOutputResults,
                                       Map<String, String> inputParams) {
        return strings.stream()
                .map(s -> substitute(s, stepOutputResults, inputParams))
                .collect(Collectors.toList());
    }

    private boolean evaluateWhen(String condition) {
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
}
