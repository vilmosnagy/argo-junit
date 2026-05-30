package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Template;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public sealed interface WorkflowNode permits DagRun, PodRun, StepsRun, UninitializedNode {

    static WorkflowNode from(String name, Template template,
                             Map<String, Template> templateMap, Set<String> constructing) {
        return from(name, template, templateMap, constructing, null);
    }

    static WorkflowNode from(String name, Template template,
                             Map<String, Template> templateMap, Set<String> constructing,
                             String owningWt) {
        if (template.getDag() != null) {
            return new DagRun(name, template, templateMap, constructing, owningWt);
        }
        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            return new StepsRun(name, template, templateMap, constructing, owningWt);
        }
        return new PodRun(name, template);
    }

    /** Current (final) attempt's direct child nodes; empty for leaf nodes. */
    default List<WorkflowNode> children() { return List.of(); }
    /** Human-readable error message set when {@link #errored()} is true; empty string otherwise. */
    default String message() {
        return children().stream()
                .map(WorkflowNode::message)
                .filter(m -> !m.isEmpty())
                .findFirst()
                .orElse("");
    }
    /** Total completed attempts (0 = not yet run, 1 = ran once, N = retried N-1 times). */
    default int attempts() { return 0; }
    /** Child-node maps from each failed attempt before the final one, in order. */
    default List<Map<String, WorkflowNode>> attemptHistory() { return List.of(); }

    String name();
    boolean succeeded();
    boolean failed();
    /** Task's {@code when} condition was false. */
    boolean skipped();
    /** Task's {@code depends} condition was false. */
    boolean omitted();
    /** Infrastructure/execution error (not a non-zero exit code). */
    boolean errored();
    boolean daemoned();
    boolean running();
    boolean pending();
    /** Mark as skipped: {@code when} condition was false. */
    void skip();
    /** Mark as omitted: {@code depends} condition was false. */
    void omit();
    CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams);
}
