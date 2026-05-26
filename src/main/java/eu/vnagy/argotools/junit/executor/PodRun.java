package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.model.Backoff;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1Probe;
import eu.vnagy.argotools.junit.model.RetryStrategy;
import eu.vnagy.argotools.junit.model.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class PodRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(PodRun.class);

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, ERRORED, SKIPPED, OMITTED, DAEMONED }

    private enum RetryPolicy { ON_FAILURE, ON_ERROR, ALWAYS }

    private record ArtifactSpec(String name, String path) {}

    private final String name;
    // Plan fields — parsed from template at construction time
    private final String image;
    private final List<String> command;
    private final String scriptSource;
    private final boolean daemon;
    private final IoK8sApiCoreV1Probe readinessProbe;
    private final List<ArtifactSpec> outputArtifactSpecs;
    private final List<ArtifactSpec> inputArtifactDecls;
    // Retry plan fields
    private final int retryLimit;       // -1 = infinite
    private final RetryPolicy retryPolicy;
    private final Duration backoffDuration;
    private final double backoffFactor;
    private final Duration backoffCap;
    private final Duration backoffMaxDuration;
    // Mutable execution state — volatile for visibility across threads
    private volatile Status status;
    private volatile int exitCode;
    private volatile String logs;
    private volatile String outputResult;
    private volatile String ip;
    private volatile GenericContainer<?> container;
    private volatile Duration duration;
    private volatile boolean daemonStopped;
    private volatile int attempts;
    private volatile Map<String, Path> collectedArtifacts = Map.of();

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
            this.daemon = false;
            this.readinessProbe = null;
        } else {
            var cont = template.getContainer();
            List<String> cmd = cont.getCommand() != null
                    ? new ArrayList<>(cont.getCommand()) : new ArrayList<>();
            if (cont.getArgs() != null) cmd.addAll(cont.getArgs());
            this.image = cont.getImage();
            this.command = List.copyOf(cmd);
            this.scriptSource = null;
            this.daemon = Boolean.TRUE.equals(template.getDaemon());
            this.readinessProbe = this.daemon ? cont.getReadinessProbe() : null;
        }
        // Output artifact specs
        List<ArtifactSpec> outs = new ArrayList<>();
        if (template.getOutputs() != null && template.getOutputs().getArtifacts() != null) {
            for (var a : template.getOutputs().getArtifacts()) {
                if (a.getPath() != null) outs.add(new ArtifactSpec(a.getName(), a.getPath()));
            }
        }
        this.outputArtifactSpecs = List.copyOf(outs);

        // Input artifact declarations
        List<ArtifactSpec> ins = new ArrayList<>();
        if (template.getInputs() != null && template.getInputs().getArtifacts() != null) {
            for (var a : template.getInputs().getArtifacts()) {
                if (a.getPath() != null) ins.add(new ArtifactSpec(a.getName(), a.getPath()));
            }
        }
        this.inputArtifactDecls = List.copyOf(ins);

        // Retry strategy
        RetryStrategy rs = template.getRetryStrategy();
        if (rs == null) {
            this.retryLimit = 0;
            this.retryPolicy = RetryPolicy.ON_FAILURE;
            this.backoffDuration = Duration.ZERO;
            this.backoffFactor = 1.0;
            this.backoffCap = Duration.ZERO;
            this.backoffMaxDuration = Duration.ZERO;
        } else {
            this.retryLimit = rs.getLimit() != null ? Integer.parseInt(rs.getLimit()) : -1;
            String pol = rs.getRetryPolicy();
            this.retryPolicy = "Always".equalsIgnoreCase(pol) ? RetryPolicy.ALWAYS
                    : "OnError".equalsIgnoreCase(pol) ? RetryPolicy.ON_ERROR
                    : RetryPolicy.ON_FAILURE;
            Backoff b = rs.getBackoff();
            if (b != null) {
                this.backoffDuration = parseDuration(b.getDuration());
                this.backoffFactor = b.getFactor() != null ? Double.parseDouble(b.getFactor()) : 1.0;
                this.backoffCap = b.getCap() != null ? parseDuration(b.getCap()) : Duration.ZERO;
                this.backoffMaxDuration = b.getMaxDuration() != null ? parseDuration(b.getMaxDuration()) : Duration.ZERO;
            } else {
                this.backoffDuration = Duration.ZERO;
                this.backoffFactor = 1.0;
                this.backoffCap = Duration.ZERO;
                this.backoffMaxDuration = Duration.ZERO;
            }
        }

        this.status = Status.PENDING;
        this.exitCode = 0;
        this.logs = "";
        this.outputResult = null;
        this.ip = null;
        this.container = null;
        this.duration = Duration.ZERO;
        this.attempts = 0;
    }

    @Override public void skip() { this.status = Status.SKIPPED; }
    @Override public void omit() { this.status = Status.OMITTED; }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                run(ctx, inputParams);
            } catch (Exception e) {
                log.error("Pod '{}': execution error", name, e);
                this.status = Status.ERRORED;
            }
            return (WorkflowNode) this;
        }, ctx.threadPool);
    }

    private void run(ExecutionContext ctx, Map<String, String> inputParams) throws Exception {
        String resolvedImage = ctx.substitute(image, inputParams);
        List<String> resolvedCommand = ctx.substituteAll(command, inputParams);
        String resolvedScript = scriptSource != null ? ctx.substitute(scriptSource, inputParams) : null;

        log.debug("Pod '{}': starting image='{}' command={}", name, resolvedImage, resolvedCommand);

        this.attempts = 0;
        Duration currentBackoff = backoffDuration;
        Instant retryStart = Instant.now();

        while (true) {
            this.attempts++;
            runAttempt(ctx, resolvedImage, resolvedCommand, resolvedScript);

            boolean shouldRetry = switch (retryPolicy) {
                case ON_FAILURE -> status == Status.FAILED;
                case ON_ERROR   -> status == Status.ERRORED;
                case ALWAYS     -> status == Status.FAILED || status == Status.ERRORED;
            };

            if (!shouldRetry) break;
            if (retryLimit >= 0 && this.attempts > retryLimit) {
                log.debug("Pod '{}': retry limit {} exhausted after {} attempt(s)", name, retryLimit, attempts);
                break;
            }
            if (!backoffMaxDuration.isZero()
                    && Duration.between(retryStart, Instant.now()).compareTo(backoffMaxDuration) >= 0) {
                log.debug("Pod '{}': maxDuration exceeded, stopping retries", name);
                break;
            }

            log.debug("Pod '{}': attempt {} {} — retrying (backoff={}ms)",
                    name, attempts, status, currentBackoff.toMillis());

            if (!currentBackoff.isZero()) {
                Thread.sleep(currentBackoff.toMillis());
                long nextMs = (long) (currentBackoff.toMillis() * backoffFactor);
                Duration next = Duration.ofMillis(nextMs);
                if (!backoffCap.isZero() && next.compareTo(backoffCap) > 0) next = backoffCap;
                currentBackoff = next;
            }
        }

        // Collect output artifacts from the final run
        if (!daemon && this.container != null) {
            Set<String> requested = ctx.requestedOutputArtifacts;
            List<ArtifactSpec> specsToCollect = requested == null ? outputArtifactSpecs
                    : outputArtifactSpecs.stream().filter(s -> requested.contains(s.name())).toList();
            if (!specsToCollect.isEmpty()) {
                Map<String, Path> collected = new LinkedHashMap<>();
                for (ArtifactSpec spec : specsToCollect) {
                    try {
                        Path artifact = extractArtifact(this.container, spec, ctx.tmpDir);
                        collected.put(spec.name(), artifact);
                        log.debug("Pod '{}': collected output artifact '{}' from '{}' → '{}'",
                                name, spec.name(), spec.path(), artifact);
                    } catch (Exception e) {
                        log.warn("Pod '{}': failed to collect output artifact '{}' from '{}'",
                                name, spec.name(), spec.path(), e);
                    }
                }
                this.collectedArtifacts = Map.copyOf(collected);
            }
        }
    }

    private void runAttempt(ExecutionContext ctx, String resolvedImage,
                            List<String> resolvedCommand, String resolvedScript) throws Exception {
        @SuppressWarnings("resource")
        GenericContainer<?> cont = new GenericContainer<>(DockerImageName.parse(resolvedImage));
        if (!resolvedCommand.isEmpty()) {
            cont.withCommand(resolvedCommand.toArray(String[]::new));
        }

        if (daemon) {
            if (readinessProbe != null && readinessProbe.getHttpGet() != null) {
                var httpGet = readinessProbe.getHttpGet();
                int probePort = Integer.parseInt(httpGet.getPort());
                String probePath = httpGet.getPath() != null ? httpGet.getPath() : "/";
                cont.addExposedPort(probePort);
                // Accept any 2xx — matches Kubernetes readiness probe semantics (e.g. InfluxDB /ping → 204)
                cont.waitingFor(Wait.forHttp(probePath)
                        .forPort(probePort)
                        .forStatusCodeMatching(code -> code >= 200 && code < 300)
                        .withStartupTimeout(Duration.ofMinutes(5)));
            } else {
                cont.waitingFor(new AbstractWaitStrategy() {
                    @Override protected void waitUntilReady() {}
                });
            }
        } else {
            cont.withStartupCheckStrategy(new OneShotStartupCheckStrategy() {
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
        }

        if (resolvedScript != null) {
            log.debug("Pod '{}': copying script source to /tmp/script", name);
            Path scriptFile = Files.createTempFile("argo-script-", "");
            Files.writeString(scriptFile, resolvedScript);
            cont.withCopyFileToContainer(MountableFile.forHostPath(scriptFile), "/tmp/script");
        }

        // Inject input artifacts before start
        for (ArtifactSpec decl : inputArtifactDecls) {
            Path hostPath = ctx.inputArtifacts.get(decl.name());
            if (hostPath != null) {
                log.debug("Pod '{}': injecting input artifact '{}' at '{}'", name, decl.name(), decl.path());
                cont.withCopyFileToContainer(MountableFile.forHostPath(hostPath), decl.path());
            }
        }

        this.status = Status.RUNNING;
        Instant start = Instant.now();
        cont.start();
        Duration elapsed = Duration.between(start, Instant.now());

        if (daemon) {
            String containerIp = cont.getCurrentContainerInfo().getNetworkSettings().getIpAddress();
            if ((containerIp == null || containerIp.isEmpty())) {
                var networks = cont.getCurrentContainerInfo().getNetworkSettings().getNetworks();
                if (networks != null && !networks.isEmpty()) {
                    containerIp = networks.values().iterator().next().getIpAddress();
                }
            }
            log.debug("Daemon pod '{}': ready ip={} duration={}s", name, containerIp, elapsed.getSeconds());
            this.ip = containerIp;
            this.container = cont;
            this.duration = elapsed;
            this.status = Status.DAEMONED;
        } else {
            int code = cont.getCurrentContainerInfo().getState().getExitCodeLong().intValue();
            String podLogs = cont.getLogs();
            String stdout = cont.getLogs(OutputFrame.OutputType.STDOUT).trim();

            log.debug("Pod '{}': attempt {} finished exitCode={} duration={}s",
                    name, attempts, code, elapsed.getSeconds());
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
    }

    /**
     * Extracts a container artifact into a temp directory using the raw Docker TAR API.
     * Supports both file and directory artifacts; returns the path of the single top-level
     * item extracted (a file or a directory).
     */
    private Path extractArtifact(GenericContainer<?> cont, ArtifactSpec spec, Path parentDir) throws Exception {
        Path tempDir = Files.createTempDirectory(parentDir, "argo-art-" + name + "-");
        try (InputStream tarStream = cont.getDockerClient()
                .copyArchiveFromContainerCmd(cont.getContainerId(), spec.path()).exec();
             TarArchiveInputStream tarInput = new TarArchiveInputStream(tarStream)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                Path target = tempDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tarInput, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try (var ls = Files.list(tempDir)) {
            return ls.findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No content extracted for artifact '" + spec.name() + "' from '" + spec.path() + "'"));
        }
    }

    private static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) return Duration.ZERO;
        s = s.trim();
        try { return Duration.ofSeconds(Long.parseLong(s)); } catch (NumberFormatException ignored) {}
        if (s.endsWith("s")) {
            try { return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1))); } catch (NumberFormatException ignored) {}
        }
        if (s.endsWith("m")) {
            try { return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1))); } catch (NumberFormatException ignored) {}
        }
        if (s.endsWith("h")) {
            try { return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1))); } catch (NumberFormatException ignored) {}
        }
        throw new IllegalArgumentException("Cannot parse Argo duration: '" + s + "'");
    }

    /** Stops the container if this is a daemon pod. No-op otherwise. */
    void stopIfDaemon() {
        if (!daemon || container == null) return;
        try {
            container.stop();
            log.debug("Daemon pod '{}': stopped", name);
        } catch (Exception e) {
            log.warn("Daemon pod '{}': failed to stop container", name, e);
        }
        this.daemonStopped = true;
    }

    @Override public String name()       { return name; }
    @Override public boolean succeeded() { return status == Status.SUCCEEDED || status == Status.DAEMONED; }
    @Override public boolean failed()    { return status == Status.FAILED; }
    @Override public boolean errored()   { return status == Status.ERRORED; }
    @Override public boolean skipped()   { return status == Status.SKIPPED; }
    @Override public boolean omitted()   { return status == Status.OMITTED; }
    @Override public boolean daemoned()  { return status == Status.DAEMONED; }
    @Override public boolean running()   { return status == Status.RUNNING; }
    @Override public boolean pending()   { return status == Status.PENDING; }

    public Status status()                 { return status; }
    public int exitCode()                  { return exitCode; }
    public String logs()                   { return logs; }
    public Optional<String> outputResult()   { return Optional.ofNullable(outputResult); }
    public Optional<String> ip()             { return Optional.ofNullable(ip); }
    public boolean isDaemonStopped()         { return daemonStopped; }
    public int attempts()                    { return attempts; }
    public Map<String, Path> collectedArtifacts() { return collectedArtifacts; }
    /** The stopped container. Logs and state remain accessible until Ryuk removes it. */
    public GenericContainer<?> container() { return container; }
    public Duration duration()             { return duration; }
}
