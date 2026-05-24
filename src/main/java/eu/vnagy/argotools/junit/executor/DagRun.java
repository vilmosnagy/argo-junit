package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class DagRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(DagRun.class);

    private record DagTaskSpec(String name, DependsExpression depends, Map<String, String> args) {}

    private final String name;
    private final List<DagTaskSpec> specs;
    private final Map<String, WorkflowNode> tasks;
    private volatile boolean skipped;

    /**
     * Plan constructor: validates structure, builds child plan nodes eagerly up to the first
     * recursion boundary, where it places {@link UninitializedNode} placeholders instead.
     * Throws {@link IllegalArgumentException} on any structural error.
     *
     * @param constructing the set of template names currently being constructed up the call stack,
     *                     used to detect recursion and stop expansion
     */
    DagRun(String name, Template template, Map<String, Template> templateMap, Set<String> constructing) {
        this.name = name;
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
            if (t.getTemplate() != null && !templateMap.containsKey(t.getTemplate())) {
                throw new IllegalArgumentException(
                        "DAG '" + name + "': task '" + t.getName()
                        + "' references unknown template '" + t.getTemplate() + "'");
            }
        }

        Set<String> nowConstructing = new HashSet<>(constructing);
        nowConstructing.add(template.getName());

        List<DagTaskSpec> builtSpecs = new ArrayList<>();
        Map<String, WorkflowNode> initialTasks = new LinkedHashMap<>();
        for (DAGTask t : topologicalSort(dagTasks)) {
            builtSpecs.add(new DagTaskSpec(t.getName(),
                    new DependsExpression(t.getDepends()), resolveArgs(t)));
            Template taskTemplate = templateMap.get(t.getTemplate());
            WorkflowNode child = (taskTemplate == null || nowConstructing.contains(taskTemplate.getName()))
                    ? new UninitializedNode(t.getName(), taskTemplate)
                    : WorkflowNode.from(t.getName(), taskTemplate, templateMap, nowConstructing);
            initialTasks.put(t.getName(), child);
        }
        this.specs = Collections.unmodifiableList(builtSpecs);
        this.tasks = Collections.unmodifiableMap(initialTasks);
    }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
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
                    log.debug("Dag '{}': task '{}' skipped by depends expression", name, spec.name());
                    tasks.get(spec.name()).skip();
                    return CompletableFuture.completedFuture(tasks.get(spec.name()));
                }

                Map<String, String> resolvedArgs = new LinkedHashMap<>();
                spec.args().forEach((k, v) -> resolvedArgs.put(k, ctx.substitute(v, inputParams)));
                return tasks.get(spec.name()).executeAsync(ctx, resolvedArgs);
            }, ctx.threadPool);

            futures.put(spec.name(), taskFuture);
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
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

    @Override public String name() { return name; }

    @Override public boolean succeeded() {
        if (skipped) return false;
        return tasks.values().stream().allMatch(n -> n.succeeded() || n.skipped());
    }
    @Override public boolean failed() {
        if (skipped) return false;
        return tasks.values().stream().anyMatch(WorkflowNode::failed);
    }
    @Override public void skip()       { this.skipped = true; }
    @Override public boolean skipped() { return skipped; }
    @Override public boolean running() {
        if (skipped) return false;
        return tasks.values().stream().anyMatch(WorkflowNode::running);
    }
    @Override public boolean pending() {
        if (skipped) return false;
        return tasks.values().stream().allMatch(WorkflowNode::pending);
    }

    private static Map<String, String> resolveArgs(DAGTask task) {
        if (task.getArguments() == null || task.getArguments().getParameters() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (var p : task.getArguments().getParameters()) {
            if (p.getValue() != null) args.put(p.getName(), p.getValue());
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
