package eu.vnagy.argotools.junit.executor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class WorkflowRun {

    private final WorkflowNode entrypoint;
    private final CompletableFuture<Void> future;

    WorkflowRun(WorkflowNode entrypoint, CompletableFuture<Void> future) {
        this.entrypoint = entrypoint;
        this.future = future;
    }

    public boolean isDone() { return future.isDone(); }

    /** Blocks until the workflow completes and returns {@code this}. */
    public WorkflowRun await() throws Exception {
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
        return this;
    }

    public boolean succeeded() { return entrypoint.succeeded(); }
    public boolean failed()    { return entrypoint.failed(); }
    public boolean running()   { return entrypoint.running(); }
    public boolean pending()   { return entrypoint.pending(); }
    public WorkflowNode entrypoint() { return entrypoint; }
}
