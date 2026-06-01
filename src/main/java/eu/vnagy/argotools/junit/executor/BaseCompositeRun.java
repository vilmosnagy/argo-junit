package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.expression.ExpressionEngine;
import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.Arguments;
import eu.vnagy.argotools.junit.model.Parameter;
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
import java.util.regex.Pattern;

/**
 * Shared state, retry loop, and child-execution helpers for composite workflow nodes
 * ({@link DagRun}, {@link StepsRun}).
 *
 * <p>Provides the common fields, the {@link WorkflowNode} status predicates, the
 * {@code doExecute} retry machinery, and protected helpers for resolving arguments,
 * downloading artifacts, and registering child outputs. Subclasses supply the
 * execution-model-specific parts via four abstract hooks.
 */
abstract class BaseCompositeRun {

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final String name;
    protected final Template originalTemplate;
    protected final RetryStrategy templateRetryStrategy;
    protected volatile int attempts;
    protected final List<Map<String, WorkflowNode>> attemptHistory = new CopyOnWriteArrayList<>();
    protected volatile Map<String, Path> collectedArtifacts = Map.of();
    protected volatile Map<String, String> collectedOutputParams = Map.of();
    protected volatile boolean skipped;
    protected volatile boolean omitted;

    protected BaseCompositeRun(String name, Template template, RetryStrategy templateRetryStrategy) {
        this.name = name;
        this.originalTemplate = template;
        this.templateRetryStrategy = templateRetryStrategy;
    }

    // -------------------------------------------------------------------------
    // Abstract hooks
    // -------------------------------------------------------------------------

    /** Returns "Dag" or "Steps" — used in log messages. */
    protected abstract String typeName();

    /** Rebuilds child nodes for a retry attempt and stores them internally. */
    protected abstract void resetNodes(ExecutionContext ctx);

    /** Returns the current child-node map (tasks for DAG, steps for steps). */
    protected abstract Map<String, WorkflowNode> currentNodes();

    /**
     * Runs one iteration — one DAG fan-out or one sequential groups sweep —
     * and returns a future that completes with {@code this}.
     */
    protected abstract CompletableFuture<WorkflowNode> executeIteration(
            ExecutionContext ctx, Map<String, String> inputParams, Map<String, WorkflowNode> nodes);

    // -------------------------------------------------------------------------
    // WorkflowNode implementations (inherited by DagRun and StepsRun)
    // -------------------------------------------------------------------------

    public String name() { return name; }
    public int attempts() { return attempts; }
    public List<Map<String, WorkflowNode>> attemptHistory() { return List.copyOf(attemptHistory); }
    public List<WorkflowNode> children() { return new ArrayList<>(currentNodes().values()); }
    public Map<String, Path> collectedArtifacts() { return collectedArtifacts; }
    public Map<String, String> collectedOutputParams() { return collectedOutputParams; }

    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        ResolvedRetry retry = ResolvedRetry.from(templateRetryStrategy, ctx.defaultRetryStrategy);
        return doExecute(ctx, inputParams, retry, 0, retry.backoffDuration(), Instant.now());
    }

    public boolean succeeded() {
        if (skipped || omitted) return false;
        return currentNodes().values().stream().allMatch(n -> n.succeeded() || n.skipped() || n.omitted());
    }
    public boolean failed() {
        if (skipped || omitted) return false;
        return currentNodes().values().stream().anyMatch(WorkflowNode::failed);
    }
    public boolean errored() {
        if (skipped || omitted) return false;
        return currentNodes().values().stream().anyMatch(WorkflowNode::errored);
    }
    public boolean daemoned()  { return false; }
    public void skip()        { this.skipped = true; }
    public void omit()        { this.omitted = true; }
    public boolean skipped()  { return skipped; }
    public boolean omitted()  { return omitted; }
    public boolean running() {
        if (skipped || omitted) return false;
        return currentNodes().values().stream().anyMatch(WorkflowNode::running);
    }
    public boolean pending() {
        if (skipped || omitted) return false;
        return currentNodes().values().stream().allMatch(WorkflowNode::pending);
    }

    // -------------------------------------------------------------------------
    // Retry loop
    // -------------------------------------------------------------------------

    private CompletableFuture<WorkflowNode> doExecute(ExecutionContext ctx, Map<String, String> inputParams,
            ResolvedRetry retry, int attempt, Duration currentBackoff, Instant retryStart) {
        if (attempt > 0) {
            log.debug("{} '{}': retry attempt {}", typeName(), name, attempt + 1);
            resetNodes(ctx);
        }
        Map<String, WorkflowNode> snapshot = currentNodes();
        return executeIteration(ctx, inputParams, snapshot).thenCompose(result -> {
            this.attempts = attempt + 1;
            if (!retry.shouldRetry(failed(), errored(), attempt + 1)) return CompletableFuture.completedFuture(result);
            if (!retry.withinMaxDuration(retryStart)) {
                log.debug("{} '{}': maxDuration exceeded, stopping retries", typeName(), name);
                return CompletableFuture.completedFuture(result);
            }
            log.debug("{} '{}': attempt {} {} — retrying (backoff={}ms)", typeName(), name, attempt + 1,
                    failed() ? "FAILED" : "ERRORED", currentBackoff.toMillis());
            attemptHistory.add(snapshot);
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

    // -------------------------------------------------------------------------
    // Child-execution helpers (used by DagRun and StepsRun)
    // -------------------------------------------------------------------------

    /** Result of {@link #resolveAndDownload}. */
    protected record ArtifactResult(Map<String, Path> resolved, String error) {}

    /** Extracts parameter arguments from a task/step's {@code arguments} block into a name→value map. */
    protected Map<String, String> resolveArgs(Arguments arguments) {
        if (arguments == null || arguments.getParameters() == null) return Map.of();
        Map<String, String> args = new LinkedHashMap<>();
        for (Parameter p : arguments.getParameters()) {
            if (p.getValue() != null) args.put(p.getName(), p.getValue());
        }
        return Collections.unmodifiableMap(args);
    }

    /** Extracts artifact arguments from a task/step's {@code arguments} block into a name→artifact map. */
    protected Map<String, Artifact> resolveArtifactArgs(Arguments arguments) {
        if (arguments == null || arguments.getArtifacts() == null) return Map.of();
        Map<String, Artifact> args = new LinkedHashMap<>();
        for (Artifact a : arguments.getArtifacts()) {
            args.put(a.getName(), a);
        }
        return Collections.unmodifiableMap(args);
    }

    /**
     * Resolves and downloads all artifact arguments for a single child node.
     *
     * <p>For {@code from:} artifacts, resolves the reference via {@code ctx.resolveArtifactFrom}.
     * For direct-location artifacts, substitutes parameters and downloads via the matching driver.
     *
     * @return a result with resolved paths; if a download failed, {@code error} is non-null
     */
    protected ArtifactResult resolveAndDownload(
            Map<String, Artifact> artifactArgs,
            ExecutionContext ctx,
            Map<String, String> inputParams,
            Map<String, String> resolvedArgs,
            String ownerName,
            String childName) {
        Map<String, Path> resolved = new LinkedHashMap<>();
        for (var entry : artifactArgs.entrySet()) {
            String artName = entry.getKey();
            Artifact art = entry.getValue();
            if (art.getFrom() != null) {
                String resolvedFrom = ctx.substitute(art.getFrom(), resolvedArgs);
                ctx.resolveArtifactFrom(resolvedFrom).ifPresent(p -> resolved.put(artName, p));
            } else {
                Map<String, String> substParams = new LinkedHashMap<>(inputParams);
                substParams.putAll(resolvedArgs);
                Artifact substituted = ExecutionContext.substituteArtifact(art, ctx, substParams);
                var driverOpt = ctx.findDriver(substituted);
                if (driverOpt.isPresent()) {
                    try {
                        Path downloaded = driverOpt.get().download(
                                substituted, ctx.tmpDir, ctx.k8sClient, ctx.namespace);
                        resolved.put(artName, downloaded);
                        log.debug("'{}': child '{}' downloaded artifact '{}' from external source",
                                ownerName, childName, artName);
                    } catch (Exception e) {
                        String s3Key = substituted.getS3() != null ? substituted.getS3().getKey() : "?";
                        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                        log.warn("'{}': child '{}' failed to download artifact '{}' (key='{}'): {}",
                                ownerName, childName, artName, s3Key, detail, e);
                        return new ArtifactResult(resolved,
                                "artifact '" + artName + "' (key='" + s3Key + "'): " + detail);
                    }
                }
            }
        }
        return new ArtifactResult(resolved, null);
    }

    /**
     * Back-fills {@code resolvedArgs} with template-declared parameter defaults that the caller
     * did not explicitly provide.
     */
    protected void injectDefaultParams(Template target, ExecutionContext ctx,
                                       Map<String, String> inputParams, Map<String, String> resolvedArgs) {
        if (target == null || target.getInputs() == null
                || target.getInputs().getParameters() == null) return;
        for (Parameter p : target.getInputs().getParameters()) {
            if (!resolvedArgs.containsKey(p.getName()) && p.getValue() != null) {
                resolvedArgs.put(p.getName(), ctx.substitute(p.getValue(), inputParams));
            }
        }
    }

    /**
     * Registers outputs from a completed child node into the local execution context maps.
     *
     * <p>For {@link PodRun} children: ip, collected artifacts, output parameters, and (when
     * {@code outputResults} is non-null) the script output result are stored.
     * For composite children: only collected artifacts are stored.
     *
     * @param outputResults target map for script output results, or {@code null} for DAG tasks
     * @return {@code result}, unchanged, for use in method-reference chains
     */
    protected WorkflowNode registerOutputs(
            WorkflowNode result,
            String childName,
            Map<String, String> ips,
            Map<String, Map<String, Path>> artifactsMap,
            Map<String, Map<String, String>> outputParams,
            Map<String, String> outputResults) {
        if (result instanceof PodRun pod) {
            pod.ip().ifPresent(ip -> {
                log.debug("child '{}': daemon ip='{}'", childName, ip);
                ips.put(childName, ip);
            });
            Map<String, Path> arts = pod.collectedArtifacts();
            if (!arts.isEmpty()) {
                log.debug("child '{}': {} output artifact(s) collected", childName, arts.size());
                artifactsMap.put(childName, arts);
            }
            Map<String, String> params = pod.collectedOutputParams();
            if (!params.isEmpty()) {
                log.debug("child '{}': {} output parameter(s) collected", childName, params.size());
                outputParams.put(childName, params);
            }
            if (outputResults != null) {
                pod.outputResult().ifPresent(r -> {
                    log.debug("child '{}': outputs.result='{}'", childName, r);
                    outputResults.put(childName, r);
                });
            }
        } else if (result instanceof BaseCompositeRun composite) {
            Map<String, Path> arts = composite.collectedArtifacts();
            if (!arts.isEmpty()) {
                log.debug("child '{}': {} output artifact(s) collected", childName, arts.size());
                artifactsMap.put(childName, arts);
            }
            Map<String, String> params = composite.collectedOutputParams();
            if (!params.isEmpty()) {
                log.debug("child '{}': {} output parameter(s) collected", childName, params.size());
                outputParams.put(childName, params);
            }
        }
        return result;
    }

    /**
     * Builds the {@code neededArtifacts} map — which child names produce artifacts that downstream
     * siblings (or the template's own outputs) consume.
     *
     * @param artifactArgsByChild one entry per child, each being that child's artifact-argument map
     * @param fromPattern         {@link ExecutionContext#TASK_ARTIFACT_FROM} or
     *                            {@link ExecutionContext#STEP_ARTIFACT_FROM}
     * @param template            the enclosing template (its {@code outputs.artifacts} are also scanned)
     */
    protected Map<String, Set<String>> buildNeededArtifacts(
            Collection<Map<String, Artifact>> artifactArgsByChild,
            Pattern fromPattern,
            Template template) {
        Map<String, Set<String>> needed = new LinkedHashMap<>();
        for (Map<String, Artifact> artArgs : artifactArgsByChild) {
            for (Artifact art : artArgs.values()) addNeeded(art, fromPattern, needed);
        }
        if (template.getOutputs() != null && template.getOutputs().getArtifacts() != null) {
            for (Artifact art : template.getOutputs().getArtifacts()) addNeeded(art, fromPattern, needed);
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        needed.forEach((k, v) -> immutable.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(immutable);
    }

    private static final java.util.regex.Pattern TASK_ARTIFACT_IN_EXPRESSION =
            java.util.regex.Pattern.compile("tasks\\['([^']+)'\\]\\.outputs\\.artifacts\\['([^']+)'\\]");

    private void addNeeded(Artifact art, Pattern fromPattern, Map<String, Set<String>> needed) {
        if (art.getFrom() != null) {
            var m = fromPattern.matcher(art.getFrom().trim());
            if (m.matches()) needed.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
        }
        if (art.getFromExpression() != null) {
            var m = TASK_ARTIFACT_IN_EXPRESSION.matcher(art.getFromExpression());
            while (m.find()) {
                needed.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(m.group(2));
            }
        }
    }

    /**
     * Resolves {@code outputs.artifacts} declared on the original template and stores them in
     * {@link #collectedArtifacts}. Handles both {@code from:} references and {@code fromExpression}.
     * Subclasses call this at the end of each iteration.
     */
    protected void resolveOutputArtifacts(ExecutionContext localCtx, Map<String, String> inputParams) {
        if (originalTemplate.getOutputs() == null
                || originalTemplate.getOutputs().getArtifacts() == null) return;
        Map<String, Path> outputs = new LinkedHashMap<>();
        for (var art : originalTemplate.getOutputs().getArtifacts()) {
            if (art.getFrom() != null) {
                localCtx.resolveArtifactFrom(art.getFrom()).ifPresent(p -> outputs.put(art.getName(), p));
            } else if (art.getFromExpression() != null) {
                Path resolved = ExpressionEngine.evaluateOutputArtifactExpression(
                        art.getFromExpression(), inputParams, localCtx.taskArtifacts);
                if (resolved != null) outputs.put(art.getName(), resolved);
            }
        }
        if (!outputs.isEmpty()) this.collectedArtifacts = Map.copyOf(outputs);
    }

    /**
     * Evaluates {@code outputs.parameters[].valueFrom.expression} on the original template and
     * stores results in {@link #collectedOutputParams}. Subclasses call this at the end of each
     * iteration.
     */
    protected void resolveOutputParameters(ExecutionContext localCtx, Map<String, String> inputParams) {
        if (originalTemplate.getOutputs() == null
                || originalTemplate.getOutputs().getParameters() == null) return;
        Map<String, String> outputs = new LinkedHashMap<>();
        for (Parameter param : originalTemplate.getOutputs().getParameters()) {
            if (param.getValueFrom() != null && param.getValueFrom().getExpression() != null) {
                String value = ExpressionEngine.evaluateOutputParamExpression(
                        param.getValueFrom().getExpression(), inputParams, localCtx.taskOutputParams);
                if (value != null) outputs.put(param.getName(), value);
            }
        }
        if (!outputs.isEmpty()) this.collectedOutputParams = Map.copyOf(outputs);
    }
}
