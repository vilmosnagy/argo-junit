package eu.vnagy.argotools.junit.executor;

import java.util.Collection;
import java.util.Map;

public final class DagRun implements WorkflowNode {

    private final String name;
    private final Map<String, WorkflowNode> tasks;

    DagRun(String name, Map<String, WorkflowNode> tasks) {
        this.name = name;
        this.tasks = tasks;
    }

    public WorkflowNode get(String taskName) {
        WorkflowNode node = tasks.get(taskName);
        if (node == null) throw new IllegalArgumentException("No task named: " + taskName);
        return node;
    }

    public Collection<WorkflowNode> tasks() {
        return tasks.values();
    }

    @Override public String name() { return name; }

    @Override public boolean succeeded() {
        return tasks.values().stream().allMatch(n -> n.succeeded() || n.skipped());
    }

    @Override public boolean failed()  { return tasks.values().stream().anyMatch(WorkflowNode::failed); }
    @Override public boolean skipped() { return false; }
    @Override public boolean running() { return tasks.values().stream().anyMatch(WorkflowNode::running); }
    @Override public boolean pending() { return tasks.values().stream().allMatch(WorkflowNode::pending); }
}
