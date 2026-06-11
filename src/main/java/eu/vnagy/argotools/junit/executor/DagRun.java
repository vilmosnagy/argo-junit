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

import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class DagRun extends BaseCompositeRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(DagRun.class);

    private record DagTaskSpec(String name, DependsExpression depends, String when,
                               Map<String, String> args, Map<String, Artifact> artifactArgs,
                               Template taskTemplate, String childOwner) {}

    private final String owningWt;
    private final List<DagTaskSpec> specs;
    private volatile Map<String, WorkflowNode> tasks;
    private final Map<String, Set<String>> neededArtifacts;

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
        super(name, template, template.getRetryStrategy());
        this.owningWt = owningWt;
        List<DAGTask> dagTasks = template.getDag().getTasks();

        Set<String> taskNames = new LinkedHashSet<>();
        for (DAGTask t : dagTasks) taskNames.add(t.getName());

        for (DAGTask t : dagTasks) {
            for (String dep : DependsExpression.from(t.getDepends(), t.getDependencies()).taskNames()) {
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
                    DependsExpression.from(t.getDepends(), t.getDependencies()), t.getWhen(),
                    resolveArgs(t.getArguments()),
                    resolveArtifactArgs(t.getArguments()),
                    taskTemplate, childOwner));
            WorkflowNode child = (taskTemplate == null || nowConstructing.contains(taskTemplate.getName()))
                    ? new UninitializedNode(t.getName(), taskTemplate, childOwner)
                    : WorkflowNode.from(t.getName(), taskTemplate, templateMap, nowConstructing, childOwner);
            initialTasks.put(t.getName(), child);
        }
        this.specs = Collections.unmodifiableList(builtSpecs);
        this.tasks = Collections.unmodifiableMap(initialTasks);

        this.neededArtifacts = buildNeededArtifacts(
                builtSpecs.stream().map(DagTaskSpec::artifactArgs).toList(),
                ExecutionContext.TASK_ARTIFACT_FROM, template);
    }

    // -------------------------------------------------------------------------
    // BaseCompositeRun hooks
    // -------------------------------------------------------------------------

    @Override protected String typeName() { return "Dag"; }

    @Override protected void resetNodes(ExecutionContext ctx) { this.tasks = buildTaskNodes(ctx); }

    @Override protected Map<String, WorkflowNode> currentNodes() { return tasks; }

    @Override
    protected CompletableFuture<WorkflowNode> executeIteration(ExecutionContext ctx,
            Map<String, String> inputParams, Map<String, WorkflowNode> nodes) {
        return runTasks(ctx, inputParams, nodes);
    }

    // -------------------------------------------------------------------------
    // DAG execution
    // -------------------------------------------------------------------------

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
                injectDefaultParams(spec.taskTemplate(), localCtx, inputParams, resolvedArgs);

                if (spec.when() != null && !spec.when().isBlank()) {
                    Map<String, String> whenParams = new LinkedHashMap<>(inputParams);
                    whenParams.putAll(resolvedArgs);
                    String evaluated = localCtx.substitute(spec.when(), whenParams);
                    if (!localCtx.evaluateWhen(evaluated)) {
                        log.debug("Dag '{}': task '{}' omitted by when expression", name, spec.name());
                        currentTasks.get(spec.name()).omit();
                        return CompletableFuture.completedFuture(currentTasks.get(spec.name()));
                    }
                }

                var artResult = resolveAndDownload(
                        spec.artifactArgs(), localCtx, inputParams, resolvedArgs, name, spec.name());
                if (artResult.error() != null) {
                    WorkflowNode node = currentTasks.get(spec.name());
                    if (node instanceof PodRun pod) pod.errorWith(artResult.error());
                    return CompletableFuture.completedFuture(node);
                }

                ExecutionContext podCtx = artResult.resolved().isEmpty()
                        ? localCtx : localCtx.withInputArtifacts(artResult.resolved());
                podCtx = podCtx.withRequestedOutputArtifacts(
                        neededArtifacts.getOrDefault(spec.name(), Set.of()));

                return currentTasks.get(spec.name()).executeAsync(podCtx, resolvedArgs);
            }, localCtx.threadPool)
            .thenApply(result -> registerOutputs(result, spec.name(),
                    localCtx.taskIps, localCtx.taskArtifacts, localCtx.taskOutputParams,
                    localCtx.taskOutputResults));

            futures.put(spec.name(), taskFuture);
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .whenComplete((_, _) -> currentTasks.values().forEach(task -> {
                    if (task instanceof PodRun pod) pod.stopIfDaemon();
                }))
                .thenApply(_ -> {
                    log.debug("Dag '{}': all tasks completed", name);
                    resolveOutputArtifacts(localCtx, inputParams);
                    resolveOutputParameters(localCtx, inputParams);
                    return (WorkflowNode) this;
                });
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    public WorkflowNode get(String taskName) {
        WorkflowNode node = tasks.get(taskName);
        if (node == null) throw new IllegalArgumentException("No task named: " + taskName);
        return node;
    }

    public Collection<WorkflowNode> tasks() { return tasks.values(); }

    // -------------------------------------------------------------------------
    // Statics
    // -------------------------------------------------------------------------

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
