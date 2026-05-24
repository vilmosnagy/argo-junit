package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PodRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(PodRun.class);

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    private final String name;
    // Plan fields — parsed from template at construction time
    private final String image;
    private final List<String> command;
    private final String scriptSource;
    // Mutable execution state — volatile for visibility across threads
    private volatile Status status;
    private volatile int exitCode;
    private volatile String logs;
    private volatile String outputResult;
    private volatile GenericContainer<?> container;
    private volatile Duration duration;

    /** Plan constructor: parses the template once so executeAsync never touches argo model classes. */
    PodRun(String name, Template template) {
        this.name = name;
        if (template.getScript() != null) {
            var script = template.getScript();
            List<String> base = script.getCommand() != null
                    ? new ArrayList<>(script.getCommand()) : new ArrayList<>();
            base.add("/tmp/script");
            this.image = script.getImage();
            this.command = List.copyOf(base);
            this.scriptSource = script.getSource();
        } else {
            var cont = template.getContainer();
            List<String> cmd = cont.getCommand() != null
                    ? new ArrayList<>(cont.getCommand()) : new ArrayList<>();
            if (cont.getArgs() != null) cmd.addAll(cont.getArgs());
            this.image = cont.getImage();
            this.command = List.copyOf(cmd);
            this.scriptSource = null;
        }
        this.status = Status.PENDING;
        this.exitCode = 0;
        this.logs = "";
        this.outputResult = null;
        this.container = null;
        this.duration = Duration.ZERO;
    }

    private PodRun(String name, Status status) {
        this.name = name;
        this.image = null;
        this.command = List.of();
        this.scriptSource = null;
        this.status = status;
        this.exitCode = status == Status.FAILED ? 1 : 0;
        this.logs = "";
        this.outputResult = null;
        this.container = null;
        this.duration = Duration.ZERO;
    }

    static PodRun succeeded(String name) { return new PodRun(name, Status.SUCCEEDED); }
    static PodRun failed(String name)    { return new PodRun(name, Status.FAILED); }
    static PodRun skipped(String name)   { return new PodRun(name, Status.SKIPPED); }

    @Override
    public void skip() { this.status = Status.SKIPPED; }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                run(ctx, inputParams);
                return (WorkflowNode) this;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, ctx.threadPool);
    }

    private void run(ExecutionContext ctx, Map<String, String> inputParams) throws Exception {
        String resolvedImage = ctx.substitute(image, inputParams);
        List<String> resolvedCommand = ctx.substituteAll(command, inputParams);
        String resolvedScript = scriptSource != null ? ctx.substitute(scriptSource, inputParams) : null;

        log.debug("Pod '{}': starting image='{}' command={}", name, resolvedImage, resolvedCommand);

        @SuppressWarnings("resource")
        GenericContainer<?> cont = new GenericContainer<>(DockerImageName.parse(resolvedImage))
                .withCommand(resolvedCommand.toArray(String[]::new))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy() {
                    // Accept any exit code — we read the actual code from container state
                    @Override
                    public StartupCheckStrategy.StartupStatus checkStartupState(
                            com.github.dockerjava.api.DockerClient dockerClient, String containerId) {
                        var state = getCurrentState(dockerClient, containerId);
                        return Boolean.TRUE.equals(state.getRunning())
                                ? StartupCheckStrategy.StartupStatus.NOT_YET_KNOWN
                                : StartupCheckStrategy.StartupStatus.SUCCESSFUL;
                    }
                }.withTimeout(Duration.ofMinutes(10)))
                .waitingFor(new AbstractWaitStrategy() {
                    @Override protected void waitUntilReady() {}
                });

        if (resolvedScript != null) {
            log.debug("Pod '{}': copying script source to /tmp/script", name);
            Path scriptFile = Files.createTempFile("argo-script-", "");
            Files.writeString(scriptFile, resolvedScript);
            cont.withCopyFileToContainer(MountableFile.forHostPath(scriptFile), "/tmp/script");
        }

        this.status = Status.RUNNING;
        Instant start = Instant.now();
        cont.start();
        Duration elapsed = Duration.between(start, Instant.now());

        int code = cont.getCurrentContainerInfo().getState().getExitCodeLong().intValue();
        String podLogs = cont.getLogs();
        String stdout = cont.getLogs(OutputFrame.OutputType.STDOUT).trim();

        log.debug("Pod '{}': finished exitCode={} duration={}s", name, code, elapsed.getSeconds());
        if (scriptSource != null) {
            log.debug("Pod '{}': stdout='{}'", name, stdout);
        }

        this.exitCode = code;
        this.logs = podLogs;
        this.outputResult = scriptSource != null ? stdout : null;
        this.container = cont;
        this.duration = elapsed;
        this.status = code == 0 ? Status.SUCCEEDED : Status.FAILED;
    }

    @Override public String name()       { return name; }
    @Override public boolean succeeded() { return status == Status.SUCCEEDED; }
    @Override public boolean failed()    { return status == Status.FAILED; }
    @Override public boolean skipped()   { return status == Status.SKIPPED; }
    @Override public boolean running()   { return status == Status.RUNNING; }
    @Override public boolean pending()   { return status == Status.PENDING; }

    public Status status()                 { return status; }
    public int exitCode()                  { return exitCode; }
    public String logs()                   { return logs; }
    public Optional<String> outputResult() { return Optional.ofNullable(outputResult); }
    /** The stopped container. Logs and state remain accessible until Ryuk removes it. */
    public GenericContainer<?> container() { return container; }
    public Duration duration()             { return duration; }
}
