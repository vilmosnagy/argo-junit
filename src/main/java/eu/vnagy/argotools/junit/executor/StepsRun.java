package eu.vnagy.argotools.junit.executor;

import java.util.Collection;
import java.util.Map;

public final class StepsRun implements WorkflowNode {

    private final String name;
    private final Map<String, WorkflowNode> steps;

    StepsRun(String name, Map<String, WorkflowNode> steps) {
        this.name = name;
        this.steps = steps;
    }

    public WorkflowNode get(String stepName) {
        WorkflowNode node = steps.get(stepName);
        if (node == null) throw new IllegalArgumentException("No step named: " + stepName);
        return node;
    }

    public Collection<WorkflowNode> steps() {
        return steps.values();
    }

    @Override public String name() { return name; }

    @Override public boolean succeeded() {
        return steps.values().stream().allMatch(n -> n.succeeded() || n.skipped());
    }

    @Override public boolean failed()  { return steps.values().stream().anyMatch(WorkflowNode::failed); }
    @Override public boolean skipped() { return false; }
    @Override public boolean running() { return steps.values().stream().anyMatch(WorkflowNode::running); }
    @Override public boolean pending() { return steps.values().stream().allMatch(WorkflowNode::pending); }
}
