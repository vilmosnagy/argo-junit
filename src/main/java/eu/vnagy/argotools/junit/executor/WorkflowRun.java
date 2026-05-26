package eu.vnagy.argotools.junit.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class WorkflowRun implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRun.class);

    private final WorkflowNode entrypoint;
    private final CompletableFuture<Void> future;
    final Path tmpDir;

    WorkflowRun(WorkflowNode entrypoint, CompletableFuture<Void> future, Path tmpDir) {
        this.entrypoint = entrypoint;
        this.future = future;
        this.tmpDir = tmpDir;
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

    /** Deletes all artifact temp files created during workflow execution. */
    @Override
    public void close() {
        try (var walk = Files.walk(tmpDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Failed to delete artifact temp path: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk artifact temp dir: {}", tmpDir, e);
        }
    }

    public boolean succeeded() { return entrypoint.succeeded(); }
    public boolean failed()    { return entrypoint.failed(); }
    public boolean running()   { return entrypoint.running(); }
    public boolean pending()   { return entrypoint.pending(); }
    public WorkflowNode entrypoint() { return entrypoint; }
}
