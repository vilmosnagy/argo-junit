package eu.vnagy.argotools.junit.executor;

public sealed interface WorkflowNode permits DagRun, PodRun, StepsRun {
    String name();
    boolean succeeded();
    boolean failed();
    boolean skipped();
    boolean running();
    boolean pending();
}
