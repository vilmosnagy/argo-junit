package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Template;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public sealed interface WorkflowNode permits DagRun, PodRun, StepsRun, UninitializedNode {

    static WorkflowNode from(String name, Template template,
                             Map<String, Template> templateMap, Set<String> constructing) {
        if (template.getDag() != null) return new DagRun(name, template, templateMap, constructing);
        if (template.getSteps() != null && !template.getSteps().isEmpty())
            return new StepsRun(name, template, templateMap, constructing);
        return new PodRun(name, template);
    }

    String name();
    boolean succeeded();
    boolean failed();
    /** Task's {@code when} condition was false. */
    boolean skipped();
    /** Task's {@code depends} condition was false. */
    boolean omitted();
    /** Infrastructure/execution error (not a non-zero exit code). */
    boolean errored();
    /** Always false — daemon tasks are not supported. */
    default boolean daemoned() { return false; }
    boolean running();
    boolean pending();
    /** Mark as skipped: {@code when} condition was false. */
    void skip();
    /** Mark as omitted: {@code depends} condition was false. */
    void omit();
    CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams);
}
