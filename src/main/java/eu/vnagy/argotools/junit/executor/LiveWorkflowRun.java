package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.WorkflowStep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public final class LiveWorkflowRun {

    private final String entrypointName;
    private final Template entrypointTemplate;
    private final Map<String, Template> templateMap;
    private final ConcurrentHashMap<String, PodRun> podStates;
    private final CompletableFuture<WorkflowRun> future;

    LiveWorkflowRun(String entrypointName, Template entrypointTemplate,
                    Map<String, Template> templateMap,
                    ConcurrentHashMap<String, PodRun> podStates,
                    CompletableFuture<WorkflowRun> future) {
        this.entrypointName = entrypointName;
        this.entrypointTemplate = entrypointTemplate;
        this.templateMap = templateMap;
        this.podStates = podStates;
        this.future = future;
    }

    /** Returns an immutable snapshot of the workflow's current execution state. */
    public WorkflowRun snapshot() {
        return new WorkflowRun(buildSnapshot(entrypointName, entrypointTemplate));
    }

    /** Blocks until the workflow completes and returns the final result. */
    public WorkflowRun await() throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
    }

    public boolean isDone() {
        return future.isDone();
    }

    private WorkflowNode buildSnapshot(String name, Template template) {
        if (template.getDag() != null) {
            Map<String, WorkflowNode> tasks = new LinkedHashMap<>();
            for (DAGTask task : template.getDag().getTasks()) {
                Template t = templateMap.get(task.getTemplate());
                tasks.put(task.getName(), buildSnapshot(task.getName(), t));
            }
            return new DagRun(name, tasks);
        }
        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            Map<String, WorkflowNode> steps = new LinkedHashMap<>();
            for (List<WorkflowStep> group : template.getSteps()) {
                for (WorkflowStep step : group) {
                    Template t = templateMap.get(step.getTemplate());
                    steps.put(step.getName(), buildSnapshot(step.getName(), t));
                }
            }
            return new StepsRun(name, steps);
        }
        return podStates.getOrDefault(name, PodRun.pending(name));
    }
}
