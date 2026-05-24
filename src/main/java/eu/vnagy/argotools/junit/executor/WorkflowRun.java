package eu.vnagy.argotools.junit.executor;

import java.util.Collection;
import java.util.List;

public final class WorkflowRun {

    private final WorkflowNode entrypoint;

    WorkflowRun(WorkflowNode entrypoint) {
        this.entrypoint = entrypoint;
    }

    public boolean succeeded() { return entrypoint.succeeded(); }
    public boolean failed()    { return entrypoint.failed(); }
    public boolean running()   { return entrypoint.running(); }
    public boolean pending()   { return entrypoint.pending(); }
    public WorkflowNode entrypoint() { return entrypoint; }
    public Collection<WorkflowNode> nodes() { return List.of(entrypoint); }
}
