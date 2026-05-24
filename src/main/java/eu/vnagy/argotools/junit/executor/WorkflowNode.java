package eu.vnagy.argotools.junit.executor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public sealed interface WorkflowNode permits DagRun, PodRun, StepsRun, UninitializedNode {
    String name();
    boolean succeeded();
    boolean failed();
    boolean skipped();
    boolean running();
    boolean pending();
    void skip();
    CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams);
}
