package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.RetryStrategy;
import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public final class DagRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(DagRun.class);

    private record DagTaskSpec(String name, DependsExpression depends, Map<String, String> args,
                               Map<String, String> artifactArgs,
                               Template taskTemplate, String childOwner) {}

    private final String name;
    private final Template originalTemplate;
    private final String owningWt;
    private final RetryStrategy templateRetryStrategy;
    private final List<DagTaskSpec> specs;
    private volatile Map<String, WorkflowNode> tasks;
    // taskName -> artifact names that downstream tasks consume from it
    private final Map<String, Set<String>> neededArtifacts;
    private volatile int attempts;
    private final List<Map<String, WorkflowNode>> attemptHistory = new CopyOnWriteArrayList<>();
    private volatile boolean skipped;
    private volatile boolean omitted;

    /**
     * Plan constructor: validates structure, builds child plan nodes eagerly up to the first
     * recursion boundary, where it places {@link UninitializedNode} placeholders instead.
     * Throws {@link IllegalArgumentException} on any structural error.
     *
     * @param constructing the set of template names currently being constructed up the call stack,
     *                     used to detect recursion and stop expansion
     */
    DagRun(String name, Template template, Map<String, Template> templateMap, Set<String> constructing,
           String owningWt) {
        this.name = name;
        this.originalTemplate = template;
        this.owningWt = owningWt;
        this.templateRetryStrategy = template.getRetryStrategy();
        List<DAGTask> dagTasks = template.getDag().getTasks();

        Set<String> taskNames = new LinkedHashSet<>();
        for (DAGTask t : dagTasks) taskNames.add(t.getName());

        for (DAGTask t : dagTasks) {
            for (String dep : new DependsExpression(t.getDepends()).taskNames()) {
                if (!taskNames.contains(dep)) {
                    throw new IllegalArgumentException(
                            "DAG '" + name + "': task '" + t.getName()
                            + "' depends on unknown task '" + dep + "'");
                }
            }
            if (t.getTemplate() != null) {
                boolean found = (owningWt != null && templateMap.containsKey(owningWt + "/" + t.getTemplate()))
                        || templateMap.containsKey(t.getTemplate());
                if (!found) throw new IllegalArgumentException(
                        "DAG '" + name + "': task '" + t.getName()
                        + "' references unknown template '" + t.getTemplate() + "'");
            }
            if (t.getTemplateRef() != null) {
                String key = t.getTemplateRef().getName() + "/" + t.getTemplateRef().getTemplate();
                if (!templateMap.containsKey(key)) throw new IllegalArgumentException(
                        "DAG '" + name + "': task '" + t.getName()
                        + "' references unresolved WorkflowTemplate '" + key
                        + "' — call getKubernetesClient() before execute()");
            }
        }

        Set<String> nowConstructing = new HashSet<>(constructing);
        nowConstructing.add(template.getName());

        List<DagTaskSpec> builtSpecs = new ArrayList<>();
        Map<String, WorkflowNode> initialTasks = new LinkedHashMap<>();
        for (DAGTask t : topologicalSort(dagTasks)) {
            Template taskTemplate = resolveTaskTemplate(t, templateMap, owningWt);
            String childOwner = t.getTemplate() != null ? owningWt
                    : t.getTemplateRef() != null ? t.getTemplateRef().getName() : null;
            builtSpecs.add(new DagTaskSpec(t.getName(),
                    new DependsExpression(t.getDepends()), resolveArgs(t), resolveArtifactArgs(t),
                    taskTemplate, childOwner));
            WorkflowNode child = (taskTemplate == null || nowConstructing.contains(taskTemplate.getName()))
                    ? new UninitializedNode(t.getName(), taskTemplate, childOwner)
                    : WorkflowNode.from(t.getName(), taskTemplate, templateMap, nowConstructing, childOwner);
            initialTasks.put(t.getName(), child);
        }
        this.specs = Collections.unmodifiableList(builtSpecs);
        this.tasks = Collections.unmodifiableMap(initialTasks);

        Map<String, Set<String>> needed = new LinkedHashMap<>();
        for (DagTaskSpec spec : builtSpecs) {
            for (String from : spec.artifactArgs().values()) {
                Matcher m = ExecutionContext.TASK_ARTIFACT_FROM.matcher(from.trim());
                if (m.matches()) {
                    needed.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
                }
            }
        }
        Map<String, Set<String>> immutableNeeded = new LinkedHashMap<>();
        needed.forEach((k, v) -> immutableNeeded.put(k, Set.copyOf(v)));
        this.neededArtifacts = Collections.unmodifiableMap(immutableNeeded);
    }

    /** Builds a fresh set of child nodes for a retry attempt, using the current template map. */
    private Map<String, WorkflowNode> buildTaskNodes(ExecutionContext ctx) {
        Set<String> nowConstructing = new HashSet<>();
        nowConstructing.add(originalTemplate.getName());
        Map<String, WorkflowNode> built = new LinkedHashMap<>();
        for (DagTaskSpec spec : specs) {
            WorkflowNode child = (spec.taskTemplate() == null
                    || nowConstructing.contains(spec.taskTemplate().getName()))
                    ? new UninitializedNode(spec.name(), spec.taskTemplate(), spec.childOwner())
                    : WorkflowNode.from(spec.name(), spec.taskTemplate(), ctx.templateMap,
                            nowConstructing, spec.childOwner());
            built.put(spec.name(), child);
        }
        return Collections.unmodifiableMap(built);
    }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        ResolvedRetry retry = ResolvedRetry.from(templateRetryStrategy, ctx.defaultRetryStrategy);
        return doExecute(ctx, inputParams, retry, 0, retry.backoffDuration(), Instant.now());
    }

    private CompletableFuture<WorkflowNode> doExecute(ExecutionContext ctx, Map<String, String> inputParams,
            ResolvedRetry retry, int attempt, Duration currentBackoff, Instant retryStart) {
        if (attempt > 0) {
            log.debug("Dag '{}': retry attempt {}", name, attempt + 1);
            this.tasks = buildTaskNodes(ctx);
        }
        Map<String, WorkflowNode> currentTasks = this.tasks;
        return runTasks(ctx, inputParams, currentTasks).thenCompose(result -> {
            this.attempts = attempt + 1;
            if (!retry.shouldRetry(failed(), errored(), attempt + 1)) return CompletableFuture.completedFuture(result);
            if (!retry.withinMaxDuration(retryStart)) {
                log.debug("Dag '{}': maxDuration exceeded, stopping retries", name);
                return CompletableFuture.completedFuture(result);
            }
            log.debug("Dag '{}': attempt {} {} — retrying (backoff={}ms)", name, attempt + 1,
                    failed() ? "FAILED" : "ERRORED", currentBackoff.toMillis());
            attemptHistory.add(currentTasks);
            Duration nextBackoff = retry.nextBackoff(currentBackoff);
            if (currentBackoff.isZero()) {
                return doExecute(ctx, inputParams, retry, attempt + 1, nextBackoff, retryStart);
            }
            return CompletableFuture.supplyAsync(() -> {
                try { Thread.sleep(currentBackoff.toMillis()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            }, ctx.threadPool).thenCompose(_ ->
                    doExecute(ctx, inputParams, retry, attempt + 1, nextBackoff, retryStart));
        });
    }

    private CompletableFuture<WorkflowNode> runTasks(ExecutionContext ctx, Map<String, String> inputParams,
            Map<String, WorkflowNode> currentTasks) {
        ExecutionContext localCtx = ctx.childScope();
        log.debug("Dag '{}': {} task(s) in topological order: {}", name, specs.size(),
                specs.stream().map(DagTaskSpec::name).collect(Collectors.joining(", ")));

        Map<String, CompletableFuture<WorkflowNode>> futures = new LinkedHashMap<>();

        for (DagTaskSpec spec : specs) {
            CompletableFuture<Void> depsReady = spec.depends().taskNames().isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(
                            spec.depends().taskNames().stream()
                                    .map(futures::get).toArray(CompletableFuture[]::new));

            log.debug("Dag '{}': task '{}' depends on {}", name, spec.name(), spec.depends().taskNames());

            CompletableFuture<WorkflowNode> taskFuture = depsReady.thenComposeAsync(_ -> {
                Map<String, WorkflowNode> depResults = new LinkedHashMap<>();
                for (String dep : spec.depends().taskNames()) depResults.put(dep, futures.get(dep).join());

                if (!spec.depends().evaluate(depResults)) {
                    log.debug("Dag '{}': task '{}' omitted by depends expression", name, spec.name());
                    currentTasks.get(spec.name()).omit();
                    return CompletableFuture.completedFuture(currentTasks.get(spec.name()));
                }

                Map<String, String> resolvedArgs = new LinkedHashMap<>();
                spec.args().forEach((k, v) -> resolvedArgs.put(k, localCtx.substitute(v, inputParams)));

                Map<String, Path> resolvedArtifacts = new LinkedHashMap<>();
                spec.artifactArgs().forEach((artName, from) ->
                        localCtx.resolveArtifactFrom(from).ifPresent(p -> resolvedArtifacts.put(artName, p)));
                ExecutionContext podCtx = resolvedArtifacts.isEmpty()
                        ? localCtx : localCtx.withInputArtifacts(resolvedArtifacts);
                podCtx = podCtx.withRequestedOutputArtifacts(
                        neededArtifacts.getOrDefault(spec.name(), Set.of()));

                return currentTasks.get(spec.name()).executeAsync(podCtx, resolvedArgs);
            }, localCtx.threadPool)
            .thenApply(result -> {
                if (result instanceof PodRun pod) {
                    pod.ip().ifPresent(ip -> {
                        log.debug("Dag '{}': task '{}' daemon ip='{}'", name, spec.name(), ip);
                        localCtx.taskIps.put(spec.name(), ip);
                    });
                    Map<String, Path> artifacts = pod.collectedArtifacts();
                    if (!artifacts.isEmpty()) {
                        log.debug("Dag '{}': task '{}' {} output artifact(s) collected",
                                name, spec.name(), artifacts.size());
                        localCtx.taskArtifacts.put(spec.name(), artifacts);
                    }
                    Map<String, String> outParams = pod.collectedOutputParams();
                    if (!outParams.isEmpty()) {
                        log.debug("Dag '{}': task '{}' {} output parameter(s) collected",
                                name, spec.name(), outParams.size());
                        localCtx.taskOutputParams.put(spec.name(), outParams);
                    }
                }
                return result;
            });

            futures.put(spec.name(), taskFuture);
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .whenComplete((_, _) -> currentTasks.values().forEach(task -> {
                    if (task instanceof PodRun pod) pod.stopIfDaemon();
                }))
                .thenApply(_ -> {
                    log.debug("Dag '{}': all tasks completed", name);
                    return (WorkflowNode) this;
                });
    }

    public WorkflowNode get(String taskName) {
        WorkflowNode node = tasks.get(taskName);
        if (node == null) throw new IllegalArgumentException("No task named: " + taskName);
        return node;
    }

    public Collection<WorkflowNode> tasks() { return tasks.values(); }

    @Override public List<WorkflowNode> children() { return new ArrayList<>(tasks.values()); }
    @Override public int attempts() { return attempts; }
    @Override public List<Map<String, WorkflowNode>> attemptHistory() { return List.copyOf(attemptHistory); }

    @Override public String name() { return name; }

    @Override public boolean succeeded() {
        if (skipped || omitted) return false;
        return tasks.values().stream().allMatch(n -> n.succeeded() || n.skipped() || n.omitted());
    }
    @Override public boolean failed() {
        if (skipped || omitted) return false;
        return tasks.values().stream().anyMatch(WorkflowNode::failed);
    }
    @Override public boolean errored() {
        if (skipped || omitted) return false;
        return tasks.values().stream().anyMatch(WorkflowNode::errored);
    }
    @Override public void skip()        { this.skipped = true; }
    @Override public void omit()        { this.omitted = true; }
    @Override public boolean daemoned()  { return false; }
    @Override public boolean skipped()  { return skipped; }
    @Override public boolean omitted()  { return omitted; }
    @Override public boolean running() {
        if (skipped || omitted) return false;
        return tasks.values().stream().anyMatch(WorkflowNode::running);
    }
    @Override public boolean pending() {
        if (skipped || omitted) return false;
        return tasks.values().stream().allMatch(WorkflowNode::pending);
    }

    private static Template resolveTaskTemplate(DAGTask task, Map<String, Template> map, String owningWt) {
        if (task.getTemplate() != null) {
            if (owningWt != null) {
                Template t = map.get(owningWt + "/" + task.getTemplate());
                if (t != null) return t;
            }
            return map.get(task.getTemplate());
        }
        if (task.getTemplateRef() != null)
            return map.get(task.getTemplateRef().getName() + "/" + task.getTemplateRef().getTemplate());
        return null;
    }

    private static Map<String, String> resolveArgs(DAGTask task) {
        if (task.getArguments() == null || task.getArguments().getParameters() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (var p : task.getArguments().getParameters()) {
            if (p.getValue() != null) args.put(p.getName(), p.getValue());
        }
        return Collections.unmodifiableMap(args);
    }

    private static Map<String, String> resolveArtifactArgs(DAGTask task) {
        if (task.getArguments() == null || task.getArguments().getArtifacts() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (var a : task.getArguments().getArtifacts()) {
            if (a.getFrom() != null) args.put(a.getName(), a.getFrom());
        }
        return Collections.unmodifiableMap(args);
    }

    private static List<DAGTask> topologicalSort(List<DAGTask> tasks) {
        Map<String, DAGTask> byName = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (DAGTask task : tasks) {
            byName.put(task.getName(), task);
            dependents.put(task.getName(), new ArrayList<>());
            inDegree.put(task.getName(), 0);
        }
        for (DAGTask task : tasks) {
            for (String dep : new DependsExpression(task.getDepends()).taskNames()) {
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

        if (sorted.size() != tasks.size()) throw new IllegalStateException("Cycle detected in DAG dependencies");
        return sorted;
    }
}
