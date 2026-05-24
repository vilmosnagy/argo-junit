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
    boolean skipped();
    boolean running();
    boolean pending();
    void skip();
    CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams);
}
