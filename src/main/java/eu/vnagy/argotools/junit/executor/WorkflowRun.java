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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkflowRun implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRun.class);

    private final WorkflowNode entrypoint;
    private final WorkflowNode exitHandlerNode; // nullable — null when spec has no onExit
    private final CompletableFuture<Void> future;
    final Path tmpDir;
    private final ConcurrentHashMap<String, String> globalOutputParams;
    private final Instant startedAt;
    private volatile Instant completedAt; // null until the workflow finishes

    WorkflowRun(WorkflowNode entrypoint, WorkflowNode exitHandlerNode,
                CompletableFuture<Void> future, Path tmpDir,
                ConcurrentHashMap<String, String> globalOutputParams,
                Instant startedAt) {
        this.entrypoint = entrypoint;
        this.exitHandlerNode = exitHandlerNode;
        this.future = future;
        this.tmpDir = tmpDir;
        this.globalOutputParams = globalOutputParams;
        this.startedAt = startedAt;
        // Stamp the completion instant so duration() can freeze once the workflow is done.
        // whenComplete on an already-completed future runs inline, so completedAt is never
        // left unset for a future that finished before this constructor ran.
        future.whenComplete((_, _) -> this.completedAt = Instant.now());
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

    /**
     * Blocks until the workflow completes or {@code timeout} elapses, then returns {@code this}.
     *
     * @throws AssertionError if the workflow does not finish within {@code timeout}
     */
    public WorkflowRun await(Duration timeout) throws Exception {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new AssertionError("Workflow did not complete within " + timeout, e);
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
    public boolean errored()   { return entrypoint.errored(); }
    public boolean running()   { return entrypoint.running(); }
    public boolean pending()   { return entrypoint.pending(); }
    public WorkflowNode entrypoint() { return entrypoint; }

    /**
     * The instant this workflow run started — stamped when {@code executeAsync()} dispatched
     * the first step. Never {@code null}.
     */
    public Instant startedAt() { return startedAt; }

    /**
     * Wall-clock time this workflow run took: from {@link #startedAt()} to completion, or from
     * {@link #startedAt()} to now while the run is still in progress.
     *
     * <p>Once the workflow finishes the value is fixed, so repeated calls return the same
     * duration. For a workflow with an {@code onExit} handler this includes the handler.
     */
    public Duration duration() {
        Instant end = completedAt;
        return Duration.between(startedAt, end != null ? end : Instant.now());
    }

    /** Returns {@code true} if this workflow had an {@code onExit} template. */
    public boolean hasExitHandler() {
        return exitHandlerNode != null;
    }

    /**
     * Returns the node that ran as the workflow's {@code onExit} handler.
     *
     * @throws IllegalStateException if the workflow had no {@code onExit} template
     */
    public WorkflowNode exitHandler() {
        if (exitHandlerNode == null)
            throw new IllegalStateException("Workflow has no onExit handler");
        return exitHandlerNode;
    }

    /**
     * Returns the value of a global output parameter registered via {@code outputs[].globalName},
     * or empty if no parameter with that name was produced.
     */
    public Optional<String> globalParameter(String name) {
        return Optional.ofNullable(globalOutputParams.get(name));
    }

    /** Returns all global output parameters produced during execution, keyed by {@code globalName}. */
    public Map<String, String> globalParameters() {
        return Map.copyOf(globalOutputParams);
    }

    /**
     * Finds a {@link PodRun} leaf anywhere in the workflow tree — including inside failed retry
     * attempts — by the short container ID (first 12 hex chars of the Docker container ID).
     * Returns empty if no container with that ID ran in this workflow.
     */
    public Optional<PodRun> findByContainerId(String shortId) {
        return findPod(entrypoint, shortId);
    }

    private static Optional<PodRun> findPod(WorkflowNode node, String shortId) {
        if (node instanceof PodRun pod) {
            boolean found = pod.podAttempts().stream()
                    .anyMatch(a -> shortId.equals(a.containerId()));
            return found ? Optional.of(pod) : Optional.empty();
        }
        for (WorkflowNode child : node.children()) {
            Optional<PodRun> found = findPod(child, shortId);
            if (found.isPresent()) return found;
        }
        for (Map<String, WorkflowNode> attempt : node.attemptHistory()) {
            for (WorkflowNode child : attempt.values()) {
                Optional<PodRun> found = findPod(child, shortId);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }
}
