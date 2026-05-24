package eu.vnagy.argotools.junit.executor;

import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.Optional;

public final class PodRun implements WorkflowNode {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    private final String name;
    private final Status status;
    private final int exitCode;
    private final String logs;
    private final String outputResult;
    private final GenericContainer<?> container;
    private final Duration duration;

    private PodRun(String name, Status status, int exitCode, String logs, String outputResult,
                   GenericContainer<?> container, Duration duration) {
        this.name = name;
        this.status = status;
        this.exitCode = exitCode;
        this.logs = logs;
        this.outputResult = outputResult;
        this.container = container;
        this.duration = duration;
    }

    static PodRun completed(String name, int exitCode, String logs, String outputResult,
                            GenericContainer<?> container, Duration duration) {
        Status s = exitCode == 0 ? Status.SUCCEEDED : Status.FAILED;
        return new PodRun(name, s, exitCode, logs, outputResult, container, duration);
    }

    static PodRun pending(String name) {
        return new PodRun(name, Status.PENDING, 0, "", null, null, Duration.ZERO);
    }

    static PodRun running(String name) {
        return new PodRun(name, Status.RUNNING, 0, "", null, null, Duration.ZERO);
    }

    static PodRun skipped(String name) {
        return new PodRun(name, Status.SKIPPED, 0, "", null, null, Duration.ZERO);
    }

    @Override public String name()       { return name; }
    @Override public boolean succeeded() { return status == Status.SUCCEEDED; }
    @Override public boolean failed()    { return status == Status.FAILED; }
    @Override public boolean skipped()   { return status == Status.SKIPPED; }
    @Override public boolean running()   { return status == Status.RUNNING; }
    @Override public boolean pending()   { return status == Status.PENDING; }

    public Status status()                          { return status; }
    public int exitCode()                           { return exitCode; }
    public String logs()                            { return logs; }
    public Optional<String> outputResult()          { return Optional.ofNullable(outputResult); }
    /** The stopped container. Logs and state remain accessible until Ryuk removes it. */
    public GenericContainer<?> container()          { return container; }
    public Duration duration()                      { return duration; }
}
