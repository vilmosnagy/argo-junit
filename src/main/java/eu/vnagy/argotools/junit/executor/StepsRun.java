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
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class StepsRun extends BaseCompositeRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(StepsRun.class);

    private record StepSpec(String name, String when, Map<String, String> args,
                            Map<String, Artifact> artifactArgs,
                            Template stepTemplate, String childOwner) {}

    private final String owningWt;
    private final List<List<StepSpec>> groups;
    private volatile Map<String, WorkflowNode> steps;
    private final Map<String, Set<String>> neededArtifacts;

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
        super(name, template, template.getRetryStrategy());
        this.owningWt = owningWt;

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
                        resolveArgs(step.getArguments()),
                        resolveArtifactArgs(step.getArguments()),
                        stepTemplate, childOwner));
                WorkflowNode child = nowConstructing.contains(stepTemplate.getName())
                        ? new UninitializedNode(step.getName(), stepTemplate, childOwner)
                        : WorkflowNode.from(step.getName(), stepTemplate, templateMap, nowConstructing, childOwner);
                initialSteps.put(step.getName(), child);
            }
            builtGroups.add(List.copyOf(specGroup));
        }
        this.groups = List.copyOf(builtGroups);
        this.steps = Collections.unmodifiableMap(initialSteps);

        this.neededArtifacts = buildNeededArtifacts(
                builtGroups.stream().flatMap(List::stream).map(StepSpec::artifactArgs).toList(),
                ExecutionContext.STEP_ARTIFACT_FROM, template);
    }

    // -------------------------------------------------------------------------
    // BaseCompositeRun hooks
    // -------------------------------------------------------------------------

    @Override protected String typeName() { return "Steps"; }

    @Override protected void resetNodes(ExecutionContext ctx) { this.steps = buildStepNodes(ctx); }

    @Override protected Map<String, WorkflowNode> currentNodes() { return steps; }

    @Override
    protected CompletableFuture<WorkflowNode> executeIteration(ExecutionContext ctx,
            Map<String, String> inputParams, Map<String, WorkflowNode> nodes) {
        return runGroups(ctx, inputParams, nodes);
    }

    // -------------------------------------------------------------------------
    // Steps execution
    // -------------------------------------------------------------------------

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
                    injectDefaultParams(spec.stepTemplate(), localCtx, inputParams, resolvedArgs);

                    var artResult = resolveAndDownload(
                            spec.artifactArgs(), localCtx, inputParams, resolvedArgs, name, spec.name());
                    if (artResult.error() != null) {
                        if (node instanceof PodRun pod) pod.errorWith(artResult.error());
                        groupFutures.add(CompletableFuture.completedFuture(node));
                        continue;
                    }

                    ExecutionContext podCtx = artResult.resolved().isEmpty()
                            ? localCtx : localCtx.withInputArtifacts(artResult.resolved());
                    podCtx = podCtx.withRequestedOutputArtifacts(
                            neededArtifacts.getOrDefault(spec.name(), Set.of()));

                    log.debug("Step '{}': running args={}", spec.name(), resolvedArgs);
                    groupFutures.add(node.executeAsync(podCtx, resolvedArgs)
                            .thenApply(result -> registerOutputs(result, spec.name(),
                                    localCtx.stepIps, localCtx.stepArtifacts, localCtx.stepOutputParams,
                                    localCtx.stepOutputResults)));
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
                    resolveOutputArtifacts(localCtx, inputParams);
                    resolveOutputParameters(localCtx, inputParams);
                    return (WorkflowNode) this;
                });
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    public WorkflowNode get(String stepName) {
        WorkflowNode node = steps.get(stepName);
        if (node == null) throw new IllegalArgumentException("No step named: " + stepName);
        return node;
    }

    public Collection<WorkflowNode> steps() { return steps.values(); }

    // -------------------------------------------------------------------------
    // Statics
    // -------------------------------------------------------------------------

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
}
