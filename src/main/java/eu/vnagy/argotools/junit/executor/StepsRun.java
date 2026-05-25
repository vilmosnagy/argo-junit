package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class StepsRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(StepsRun.class);

    private record StepSpec(String name, String when, Map<String, String> args) {}

    private final String name;
    private final List<List<StepSpec>> groups;
    private final Map<String, WorkflowNode> steps;
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
    StepsRun(String name, Template template, Map<String, Template> templateMap, Set<String> constructing) {
        this.name = name;
        Set<String> nowConstructing = new HashSet<>(constructing);
        nowConstructing.add(template.getName());

        List<List<StepSpec>> builtGroups = new ArrayList<>();
        Map<String, WorkflowNode> initialSteps = new LinkedHashMap<>();
        for (List<WorkflowStep> group : template.getSteps()) {
            List<StepSpec> specGroup = new ArrayList<>();
            for (WorkflowStep step : group) {
                Template stepTemplate = templateMap.get(step.getTemplate());
                if (stepTemplate == null) {
                    throw new IllegalArgumentException(
                            "Steps '" + name + "': step '" + step.getName()
                            + "' references unknown template '" + step.getTemplate() + "'");
                }
                specGroup.add(new StepSpec(step.getName(), step.getWhen(), parseArgs(step)));
                WorkflowNode child = nowConstructing.contains(stepTemplate.getName())
                        ? new UninitializedNode(step.getName(), stepTemplate)
                        : WorkflowNode.from(step.getName(), stepTemplate, templateMap, nowConstructing);
                initialSteps.put(step.getName(), child);
            }
            builtGroups.add(List.copyOf(specGroup));
        }
        this.groups = List.copyOf(builtGroups);
        this.steps = Collections.unmodifiableMap(initialSteps);
    }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        log.debug("Steps '{}': {} group(s)", name, groups.size());

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (int i = 0; i < groups.size(); i++) {
            final List<StepSpec> group = groups.get(i);
            final int groupIndex = i;

            chain = chain.thenComposeAsync(_ -> {
                log.debug("Steps '{}': executing group {} [{} step(s)]", name, groupIndex, group.size());
                List<CompletableFuture<?>> groupFutures = new ArrayList<>();

                for (StepSpec spec : group) {
                    WorkflowNode node = steps.get(spec.name());

                    if (spec.when() != null) {
                        String substituted = ctx.substitute(spec.when(), inputParams);
                        boolean run = ctx.evaluateWhen(substituted);
                        log.debug("Step '{}': when='{}' → '{}' → {}", spec.name(), spec.when(),
                                substituted, run ? "run" : "skip");
                        if (!run) {
                            node.skip();
                            groupFutures.add(CompletableFuture.completedFuture(node));
                            continue;
                        }
                    }

                    Map<String, String> resolvedArgs = new LinkedHashMap<>();
                    spec.args().forEach((k, v) -> resolvedArgs.put(k, ctx.substitute(v, inputParams)));

                    log.debug("Step '{}': running args={}", spec.name(), resolvedArgs);
                    groupFutures.add(
                            node.executeAsync(ctx, resolvedArgs)
                                    .thenApply(result -> {
                                        if (result instanceof PodRun pod) {
                                            pod.outputResult().ifPresent(r -> {
                                                log.debug("Step '{}': outputs.result='{}'", pod.name(), r);
                                                ctx.stepOutputResults.put(spec.name(), r);
                                            });
                                        }
                                        return result;
                                    }));
                }

                return CompletableFuture.allOf(groupFutures.toArray(new CompletableFuture[0]));
            }, ctx.threadPool);
        }

        return chain.thenApply(_ -> {
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

    private static Map<String, String> parseArgs(WorkflowStep step) {
        if (step.getArguments() == null || step.getArguments().getParameters() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (var p : step.getArguments().getParameters()) {
            if (p.getValue() != null) args.put(p.getName(), p.getValue());
        }
        return Collections.unmodifiableMap(args);
    }
}
