package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.RetryStrategy;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;

public final class StepsRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(StepsRun.class);

    private record StepSpec(String name, String when, Map<String, String> args,
                            Map<String, String> artifactArgs,
                            Template stepTemplate, String childOwner) {}

    private final String name;
    private final Template originalTemplate;
    private final String owningWt;
    private final RetryStrategy templateRetryStrategy;
    private final List<List<StepSpec>> groups;
    private volatile Map<String, WorkflowNode> steps;
    // stepName -> artifact names that downstream steps consume from it
    private final Map<String, Set<String>> neededArtifacts;
    private volatile int attempts;
    private final List<Map<String, WorkflowNode>> attemptHistory = new CopyOnWriteArrayList<>();
    private volatile boolean skipped;
    private volatile boolean omitted;

    /**
     * Plan constructor: validates template references, builds child plan nodes eagerly up to the
     * first recursion boundary, where it places {@link UninitializedNode} placeholders instead.
     * Throws {@link IllegalArgumentException} on any structural error.
     *
     * @param constructing the set of template names currently being constructed up the call stack,
     *                     used to detect recursion and stop expansion
     */
    StepsRun(String name, Template template, Map<String, Template> templateMap, Set<String> constructing,
             String owningWt) {
        this.name = name;
        this.originalTemplate = template;
        this.owningWt = owningWt;
        this.templateRetryStrategy = template.getRetryStrategy();

        Set<String> nowConstructing = new HashSet<>(constructing);
        nowConstructing.add(template.getName());

        List<List<StepSpec>> builtGroups = new ArrayList<>();
        Map<String, WorkflowNode> initialSteps = new LinkedHashMap<>();
        for (List<WorkflowStep> group : template.getSteps()) {
            List<StepSpec> specGroup = new ArrayList<>();
            for (WorkflowStep step : group) {
                Template stepTemplate = resolveStepTemplate(step, templateMap, name, owningWt);
                String childOwner = step.getTemplate() != null ? owningWt
                        : step.getTemplateRef() != null ? step.getTemplateRef().getName() : null;
                specGroup.add(new StepSpec(step.getName(), step.getWhen(),
                        parseArgs(step), parseArtifactArgs(step), stepTemplate, childOwner));
                WorkflowNode child = nowConstructing.contains(stepTemplate.getName())
                        ? new UninitializedNode(step.getName(), stepTemplate, childOwner)
                        : WorkflowNode.from(step.getName(), stepTemplate, templateMap, nowConstructing, childOwner);
                initialSteps.put(step.getName(), child);
            }
            builtGroups.add(List.copyOf(specGroup));
        }
        this.groups = List.copyOf(builtGroups);
        this.steps = Collections.unmodifiableMap(initialSteps);

        Map<String, Set<String>> needed = new LinkedHashMap<>();
        for (List<StepSpec> group : builtGroups) {
            for (StepSpec spec : group) {
                for (String from : spec.artifactArgs().values()) {
                    Matcher m = ExecutionContext.STEP_ARTIFACT_FROM.matcher(from.trim());
                    if (m.matches()) {
                        needed.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
                    }
                }
            }
        }
        Map<String, Set<String>> immutableNeeded = new LinkedHashMap<>();
        needed.forEach((k, v) -> immutableNeeded.put(k, Set.copyOf(v)));
        this.neededArtifacts = Collections.unmodifiableMap(immutableNeeded);
    }

    /** Builds a fresh set of child nodes for a retry attempt, using the current template map. */
    private Map<String, WorkflowNode> buildStepNodes(ExecutionContext ctx) {
        Set<String> nowConstructing = new HashSet<>();
        nowConstructing.add(originalTemplate.getName());
        Map<String, WorkflowNode> built = new LinkedHashMap<>();
        for (List<StepSpec> group : groups) {
            for (StepSpec spec : group) {
                WorkflowNode child = nowConstructing.contains(spec.stepTemplate().getName())
                        ? new UninitializedNode(spec.name(), spec.stepTemplate(), spec.childOwner())
                        : WorkflowNode.from(spec.name(), spec.stepTemplate(), ctx.templateMap,
                                nowConstructing, spec.childOwner());
                built.put(spec.name(), child);
            }
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
            log.debug("Steps '{}': retry attempt {}", name, attempt + 1);
            this.steps = buildStepNodes(ctx);
        }
        Map<String, WorkflowNode> currentSteps = this.steps;
        return runGroups(ctx, inputParams, currentSteps).thenCompose(result -> {
            this.attempts = attempt + 1;
            if (!retry.shouldRetry(failed(), errored(), attempt + 1)) return CompletableFuture.completedFuture(result);
            if (!retry.withinMaxDuration(retryStart)) {
                log.debug("Steps '{}': maxDuration exceeded, stopping retries", name);
                return CompletableFuture.completedFuture(result);
            }
            log.debug("Steps '{}': attempt {} {} — retrying (backoff={}ms)", name, attempt + 1,
                    failed() ? "FAILED" : "ERRORED", currentBackoff.toMillis());
            attemptHistory.add(currentSteps);
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

    private CompletableFuture<WorkflowNode> runGroups(ExecutionContext ctx, Map<String, String> inputParams,
            Map<String, WorkflowNode> currentSteps) {
        ExecutionContext localCtx = ctx.childScope();
        log.debug("Steps '{}': {} group(s)", name, groups.size());

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (int i = 0; i < groups.size(); i++) {
            final List<StepSpec> group = groups.get(i);
            final int groupIndex = i;

            chain = chain.thenComposeAsync(_ -> {
                log.debug("Steps '{}': executing group {} [{} step(s)]", name, groupIndex, group.size());
                List<CompletableFuture<?>> groupFutures = new ArrayList<>();

                for (StepSpec spec : group) {
                    WorkflowNode node = currentSteps.get(spec.name());

                    if (spec.when() != null) {
                        String substituted = localCtx.substitute(spec.when(), inputParams);
                        boolean run = localCtx.evaluateWhen(substituted);
                        log.debug("Step '{}': when='{}' → '{}' → {}", spec.name(), spec.when(),
                                substituted, run ? "run" : "skip");
                        if (!run) {
                            node.skip();
                            groupFutures.add(CompletableFuture.completedFuture(node));
                            continue;
                        }
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

                    log.debug("Step '{}': running args={}", spec.name(), resolvedArgs);
                    groupFutures.add(
                            node.executeAsync(podCtx, resolvedArgs)
                                    .thenApply(result -> {
                                        if (result instanceof PodRun pod) {
                                            pod.outputResult().ifPresent(r -> {
                                                log.debug("Step '{}': outputs.result='{}'", pod.name(), r);
                                                localCtx.stepOutputResults.put(spec.name(), r);
                                            });
                                            pod.ip().ifPresent(ip -> {
                                                log.debug("Step '{}': daemon ip='{}'", pod.name(), ip);
                                                localCtx.stepIps.put(spec.name(), ip);
                                            });
                                            Map<String, Path> artifacts = pod.collectedArtifacts();
                                            if (!artifacts.isEmpty()) {
                                                log.debug("Step '{}': {} output artifact(s) collected",
                                                        spec.name(), artifacts.size());
                                                localCtx.stepArtifacts.put(spec.name(), artifacts);
                                            }
                                            Map<String, String> outParams = pod.collectedOutputParams();
                                            if (!outParams.isEmpty()) {
                                                log.debug("Step '{}': {} output parameter(s) collected",
                                                        spec.name(), outParams.size());
                                                localCtx.stepOutputParams.put(spec.name(), outParams);
                                            }
                                        }
                                        return result;
                                    }));
                }

                return CompletableFuture.allOf(groupFutures.toArray(new CompletableFuture[0]));
            }, localCtx.threadPool);
        }

        return chain
                .whenComplete((_, _) -> currentSteps.values().forEach(step -> {
                    if (step instanceof PodRun pod) pod.stopIfDaemon();
                }))
                .thenApply(_ -> {
                    log.debug("Steps '{}': all groups completed", name);
                    return (WorkflowNode) this;
                });
    }

    public WorkflowNode get(String stepName) {
        WorkflowNode node = steps.get(stepName);
        if (node == null) throw new IllegalArgumentException("No step named: " + stepName);
        return node;
    }

    public Collection<WorkflowNode> steps() { return steps.values(); }

    @Override public List<WorkflowNode> children() { return new ArrayList<>(steps.values()); }
    @Override public int attempts() { return attempts; }
    @Override public List<Map<String, WorkflowNode>> attemptHistory() { return List.copyOf(attemptHistory); }

    @Override public String name() { return name; }

    @Override public boolean succeeded() {
        if (skipped || omitted) return false;
        return steps.values().stream().allMatch(n -> n.succeeded() || n.skipped() || n.omitted());
    }
    @Override public boolean failed() {
        if (skipped || omitted) return false;
        return steps.values().stream().anyMatch(WorkflowNode::failed);
    }
    @Override public boolean errored() {
        if (skipped || omitted) return false;
        return steps.values().stream().anyMatch(WorkflowNode::errored);
    }
    @Override public boolean daemoned()  { return false; }
    @Override public void skip()        { this.skipped = true; }
    @Override public void omit()        { this.omitted = true; }
    @Override public boolean skipped()  { return skipped; }
    @Override public boolean omitted()  { return omitted; }
    @Override public boolean running() {
        if (skipped || omitted) return false;
        return steps.values().stream().anyMatch(WorkflowNode::running);
    }
    @Override public boolean pending() {
        if (skipped || omitted) return false;
        return steps.values().stream().allMatch(WorkflowNode::pending);
    }

    private static Template resolveStepTemplate(WorkflowStep step, Map<String, Template> map,
                                                String stepsName, String owningWt) {
        if (step.getTemplate() != null) {
            if (owningWt != null) {
                Template t = map.get(owningWt + "/" + step.getTemplate());
                if (t != null) return t;
            }
            Template t = map.get(step.getTemplate());
            if (t == null) throw new IllegalArgumentException(
                    "Steps '" + stepsName + "': step '" + step.getName()
                    + "' references unknown template '" + step.getTemplate() + "'");
            return t;
        }
        if (step.getTemplateRef() != null) {
            String key = step.getTemplateRef().getName() + "/" + step.getTemplateRef().getTemplate();
            Template t = map.get(key);
            if (t == null) throw new IllegalArgumentException(
                    "Steps '" + stepsName + "': step '" + step.getName()
                    + "' references unresolved WorkflowTemplate '"
                    + step.getTemplateRef().getName() + "/" + step.getTemplateRef().getTemplate()
                    + "' — call getKubernetesClient() before execute()");
            return t;
        }
        throw new IllegalArgumentException(
                "Steps '" + stepsName + "': step '" + step.getName() + "' has neither template nor templateRef");
    }

    private static Map<String, String> parseArgs(WorkflowStep step) {
        if (step.getArguments() == null || step.getArguments().getParameters() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (var p : step.getArguments().getParameters()) {
            if (p.getValue() != null) args.put(p.getName(), p.getValue());
        }
        return Collections.unmodifiableMap(args);
    }

    private static Map<String, String> parseArtifactArgs(WorkflowStep step) {
        if (step.getArguments() == null || step.getArguments().getArtifacts() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (var a : step.getArguments().getArtifacts()) {
            if (a.getFrom() != null) args.put(a.getName(), a.getFrom());
        }
        return Collections.unmodifiableMap(args);
    }
}
