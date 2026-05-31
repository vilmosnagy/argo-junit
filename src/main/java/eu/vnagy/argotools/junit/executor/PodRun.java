package eu.vnagy.argotools.junit.executor;

import eu.vnagy.argotools.junit.artifact.ArtifactDriver;
import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1KeyToPath;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1Probe;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1Volume;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1VolumeMount;
import eu.vnagy.argotools.junit.model.Parameter;
import eu.vnagy.argotools.junit.model.RetryStrategy;
import eu.vnagy.argotools.junit.model.Template;
import org.testcontainers.containers.BindMode;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
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
import java.util.concurrent.CopyOnWriteArrayList;

public final class PodRun implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(PodRun.class);

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, ERRORED, SKIPPED, OMITTED, DAEMONED }


    private record ArtifactSpec(String name, String path, Integer mode, Artifact artifact) {}

    private record OutputParamSpec(String name, String path, String defaultValue) {}

    private record ConfigMapRef(String paramName, String cmName, String key) {}

    /** An env var whose value must be resolved from a ConfigMap or Secret at runtime. */
    private record EnvRef(String envName, boolean isSecret, String resourceName, String key) {}

    /** A volume mount declared on the container/script template. */
    private record VolumeMountSpec(String volumeName, String mountPath, String subPath, boolean readOnly) {}

    /** Per-attempt execution record: populated after each container run completes. */
    public record Attempt(String containerId, Duration duration, boolean succeeded, boolean errored, int exitCode) {}

    private final String name;
    // Plan fields — parsed from template at construction time
    private final String image;
    private final List<String> command;
    private final String scriptSource;
    private final boolean daemon;
    private final IoK8sApiCoreV1Probe readinessProbe;
    private final List<String> execProbeCommand;
    private final List<ArtifactSpec> outputArtifactSpecs;
    private final List<OutputParamSpec> outputParamSpecs;
    private final List<ArtifactSpec> inputArtifactDecls;
    private final List<ConfigMapRef> configMapRefs;
    private final Map<String, String> plainEnv;
    private final List<EnvRef> envRefs;
    private final Map<String, IoK8sApiCoreV1Volume> templateVolumes;
    private final List<VolumeMountSpec> volumeMountSpecs;
    // Retry — raw template field; effective strategy (with templateDefaults fallback) resolved at run() time
    private final RetryStrategy templateRetryStrategy;
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
    private final List<Attempt> podAttempts = new CopyOnWriteArrayList<>();
    private volatile Map<String, Path> collectedArtifacts = Map.of();
    private volatile Map<String, String> collectedOutputParams = Map.of();
    private volatile String message = "";

    /** Plan constructor: parses the template once so executeAsync never touches argo model classes. */
    PodRun(String name, Template template) {
        this.name = name;
        if (template.getScript() != null) {
            var script = template.getScript();
            this.image = script.getImage();
            // command becomes the container ENTRYPOINT (overriding the image's baked-in one);
            // /tmp/script is passed as CMD in runAttempt
            this.command = script.getCommand() != null
                    ? List.copyOf(script.getCommand()) : List.of();
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
        if (this.daemon && this.readinessProbe != null && this.readinessProbe.getExec() != null) {
            var cmd = this.readinessProbe.getExec().getCommand();
            this.execProbeCommand = cmd != null ? List.copyOf(cmd) : List.of();
        } else {
            this.execProbeCommand = null;
        }
        // Output artifact specs (mode not applicable for outputs)
        List<ArtifactSpec> outs = new ArrayList<>();
        if (template.getOutputs() != null && template.getOutputs().getArtifacts() != null) {
            for (var a : template.getOutputs().getArtifacts()) {
                if (a.getPath() != null) outs.add(new ArtifactSpec(a.getName(), a.getPath(), null, a));
            }
        }
        this.outputArtifactSpecs = List.copyOf(outs);

        // Output parameter specs — read file content from the container after it exits
        List<OutputParamSpec> outParams = new ArrayList<>();
        if (template.getOutputs() != null && template.getOutputs().getParameters() != null) {
            for (Parameter p : template.getOutputs().getParameters()) {
                if (p.getValueFrom() != null && p.getValueFrom().getPath() != null) {
                    outParams.add(new OutputParamSpec(
                            p.getName(), p.getValueFrom().getPath(), p.getValueFrom().getDefault()));
                }
            }
        }
        this.outputParamSpecs = List.copyOf(outParams);

        // Input artifact declarations — mode is applied when copying into the container
        List<ArtifactSpec> ins = new ArrayList<>();
        if (template.getInputs() != null && template.getInputs().getArtifacts() != null) {
            for (var a : template.getInputs().getArtifacts()) {
                if (a.getPath() != null) ins.add(new ArtifactSpec(a.getName(), a.getPath(), a.getMode(), a));
            }
        }
        this.inputArtifactDecls = List.copyOf(ins);

        // configMapKeyRef input parameters — resolved from the Kubernetes API at runtime
        List<ConfigMapRef> cmRefs = new ArrayList<>();
        if (template.getInputs() != null && template.getInputs().getParameters() != null) {
            for (var p : template.getInputs().getParameters()) {
                if (p.getValueFrom() != null && p.getValueFrom().getConfigMapKeyRef() != null) {
                    var ref = p.getValueFrom().getConfigMapKeyRef();
                    cmRefs.add(new ConfigMapRef(p.getName(), ref.getName(), ref.getKey()));
                }
            }
        }
        this.configMapRefs = List.copyOf(cmRefs);

        // env[] — plain value: entries passed directly; valueFrom: entries resolved at runtime
        var envVars = template.getScript() != null
                ? template.getScript().getEnv()
                : (template.getContainer() != null ? template.getContainer().getEnv() : null);
        Map<String, String> plainEnvMap = new LinkedHashMap<>();
        List<EnvRef> envRefList = new ArrayList<>();
        if (envVars != null) {
            for (var e : envVars) {
                if (e.getValueFrom() == null) {
                    if (e.getValue() != null) plainEnvMap.put(e.getName(), e.getValue());
                    continue;
                }
                var src = e.getValueFrom();
                if (src.getConfigMapKeyRef() != null) {
                    var ref = src.getConfigMapKeyRef();
                    envRefList.add(new EnvRef(e.getName(), false, ref.getName(), ref.getKey()));
                } else if (src.getSecretKeyRef() != null) {
                    var ref = src.getSecretKeyRef();
                    envRefList.add(new EnvRef(e.getName(), true, ref.getName(), ref.getKey()));
                }
            }
        }
        this.plainEnv = Map.copyOf(plainEnvMap);
        this.envRefs = List.copyOf(envRefList);

        // template-level volumes — scoped to this template, merged with spec.volumes at run time
        Map<String, IoK8sApiCoreV1Volume> tmplVols = new LinkedHashMap<>();
        if (template.getVolumes() != null) {
            for (IoK8sApiCoreV1Volume v : template.getVolumes()) {
                tmplVols.put(v.getName(), v);
            }
        }
        this.templateVolumes = Map.copyOf(tmplVols);

        // volumeMounts — parsed from container or script template
        var vmList = template.getScript() != null
                ? template.getScript().getVolumeMounts()
                : (template.getContainer() != null ? template.getContainer().getVolumeMounts() : null);
        List<VolumeMountSpec> vms = new ArrayList<>();
        if (vmList != null) {
            for (var vm : vmList) {
                vms.add(new VolumeMountSpec(
                        vm.getName(), vm.getMountPath(), vm.getSubPath(),
                        Boolean.TRUE.equals(vm.getReadOnly())));
            }
        }
        this.volumeMountSpecs = List.copyOf(vms);

        this.templateRetryStrategy = template.getRetryStrategy();

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
    /** Pre-empt execution with an error message (e.g. artifact download failure). */
    void errorWith(String msg) { this.message = msg; this.status = Status.ERRORED; }
    @Override public String message() { return message; }

    @Override
    public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                run(ctx, inputParams);
            } catch (Exception e) {
                log.error("Pod '{}': execution error", name, e);
                this.status = Status.ERRORED;
                this.message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            return (WorkflowNode) this;
        }, ctx.threadPool);
    }

    private void run(ExecutionContext ctx, Map<String, String> inputParams) throws Exception {
        ResolvedRetry retry = ResolvedRetry.from(templateRetryStrategy, ctx.defaultRetryStrategy);
        // Resolve any configMapKeyRef parameters not already provided as explicit args
        if (!configMapRefs.isEmpty()) {
            Map<String, String> enriched = new LinkedHashMap<>(inputParams);
            for (ConfigMapRef ref : configMapRefs) {
                if (!enriched.containsKey(ref.paramName())) {
                    enriched.put(ref.paramName(),
                            ctx.resolveConfigMapKey(ctx.namespace, ref.cmName(), ref.key()));
                }
            }
            inputParams = enriched;
        }

        String resolvedImage = ctx.substitute(image, inputParams);
        List<String> resolvedCommand = ctx.substituteAll(command, inputParams);
        String resolvedScript = scriptSource != null ? ctx.substitute(scriptSource, inputParams) : null;

        if (resolvedScript != null) {
            log.debug("Pod '{}': starting image='{}' entrypoint={} script=/tmp/script",
                    name, resolvedImage, resolvedCommand);
        } else {
            log.debug("Pod '{}': starting image='{}' command={}", name, resolvedImage, resolvedCommand);
        }

        // Resolve env vars before the retry loop
        Map<String, String> resolvedEnv = new LinkedHashMap<>();
        // Plain value entries (may still contain inputs.parameters placeholders)
        for (var e : plainEnv.entrySet()) {
            resolvedEnv.put(e.getKey(), ctx.substitute(e.getValue(), inputParams));
        }
        // Ref-based entries resolved from ConfigMap / Secret (override plain if same name)
        for (EnvRef ref : envRefs) {
            String resolvedName = ctx.substitute(ref.resourceName(), inputParams);
            String resolvedKey  = ctx.substitute(ref.key(), inputParams);
            resolvedEnv.put(ref.envName(), ref.isSecret()
                    ? ctx.resolveSecretKey(ctx.namespace, resolvedName, resolvedKey)
                    : ctx.resolveConfigMapKey(ctx.namespace, resolvedName, resolvedKey));
        }

        // Download external input artifacts (S3, etc.) onto the host before the retry loop
        Map<String, Path> effectiveInputs = new LinkedHashMap<>(ctx.inputArtifacts);
        for (ArtifactSpec decl : inputArtifactDecls) {
            if (decl.artifact() != null) {
                Artifact substituted = ExecutionContext.substituteArtifact(decl.artifact(), ctx, inputParams);
                Optional<ArtifactDriver> maybeDriver = ctx.findDriver(substituted);
                if (maybeDriver.isPresent()) {
                    Path downloaded = maybeDriver.get().download(
                            substituted, ctx.tmpDir, ctx.k8sClient, ctx.namespace);
                    effectiveInputs.put(decl.name(), downloaded);
                    log.debug("Pod '{}': downloaded external input artifact '{}' → '{}'",
                            name, decl.name(), downloaded);
                }
            }
        }

        Map<String, Path> materializedVolumes = materializeVolumes(ctx, inputParams);

        this.attempts = 0;
        Duration currentBackoff = retry.backoffDuration();
        Instant retryStart = Instant.now();

        while (true) {
            this.attempts++;
            runAttempt(ctx, resolvedImage, resolvedCommand, resolvedScript, effectiveInputs, resolvedEnv, materializedVolumes);

            if (!retry.shouldRetry(status == Status.FAILED, status == Status.ERRORED, this.attempts)) break;
            if (!retry.withinMaxDuration(retryStart)) {
                log.debug("Pod '{}': maxDuration exceeded, stopping retries", name);
                break;
            }

            log.debug("Pod '{}': attempt {} {} — retrying (backoff={}ms)",
                    name, attempts, status, currentBackoff.toMillis());

            if (!currentBackoff.isZero()) {
                Thread.sleep(currentBackoff.toMillis());
                currentBackoff = retry.nextBackoff(currentBackoff);
            }
        }

        // Collect output artifacts from the final run
        if (!daemon && this.container != null) {
            Set<String> requested = ctx.requestedOutputArtifacts;
            // Always collect artifacts that have an external location (S3 etc.) regardless of
            // whether a downstream step requested them — they need to be uploaded unconditionally.
            List<ArtifactSpec> specsToCollect;
            if (requested == null) {
                specsToCollect = outputArtifactSpecs;
            } else {
                specsToCollect = outputArtifactSpecs.stream()
                        .filter(s -> requested.contains(s.name())
                                  || (s.artifact() != null && ctx.findDriver(s.artifact()).isPresent()))
                        .toList();
            }
            if (!specsToCollect.isEmpty()) {
                Map<String, Path> collected = new LinkedHashMap<>();
                for (ArtifactSpec spec : specsToCollect) {
                    try {
                        Path extracted = extractArtifact(this.container, spec, ctx.tmpDir);
                        collected.put(spec.name(), extracted);
                        log.debug("Pod '{}': collected output artifact '{}' from '{}' → '{}'",
                                name, spec.name(), spec.path(), extracted);
                        if (spec.artifact() != null) {
                            Optional<ArtifactDriver> maybeDriver = ctx.findDriver(spec.artifact());
                            if (maybeDriver.isPresent()) {
                                maybeDriver.get().upload(
                                        spec.artifact(), extracted, ctx.k8sClient, ctx.namespace);
                                log.debug("Pod '{}': uploaded output artifact '{}' to external storage",
                                        name, spec.name());
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Pod '{}': failed to collect output artifact '{}' from '{}'",
                                name, spec.name(), spec.path(), e);
                    }
                }
                this.collectedArtifacts = Map.copyOf(collected);
            }
        }

        // Collect output parameters (file-backed) after a successful run
        if (!daemon && this.container != null && this.status == Status.SUCCEEDED
                && !outputParamSpecs.isEmpty()) {
            Map<String, String> params = new LinkedHashMap<>();
            for (OutputParamSpec spec : outputParamSpecs) {
                try {
                    String value = readFileFromContainer(this.container, spec.path());
                    params.put(spec.name(), value);
                    log.debug("Pod '{}': output parameter '{}' = '{}'", name, spec.name(), value);
                } catch (Exception e) {
                    if (spec.defaultValue() != null) {
                        params.put(spec.name(), spec.defaultValue());
                        log.debug("Pod '{}': output parameter '{}' defaulted to '{}'",
                                name, spec.name(), spec.defaultValue());
                    } else {
                        log.warn("Pod '{}': failed to read output parameter '{}' from '{}'",
                                name, spec.name(), spec.path(), e);
                    }
                }
            }
            this.collectedOutputParams = Map.copyOf(params);
        }
    }

    private void runAttempt(ExecutionContext ctx, String resolvedImage,
                            List<String> resolvedCommand, String resolvedScript,
                            Map<String, Path> effectiveInputs,
                            Map<String, String> resolvedEnv,
                            Map<String, Path> materializedVolumes) throws Exception {
        @SuppressWarnings("resource")
        GenericContainer<?> cont = new GenericContainer<>(DockerImageName.parse(resolvedImage));
        if (resolvedScript != null) {
            // Script template: override the image ENTRYPOINT with the template's command array
            // so that a baked-in ENTRYPOINT does not prepend itself to the invocation.
            if (!resolvedCommand.isEmpty()) {
                cont.withCreateContainerCmdModifier(
                        cmd -> cmd.withEntrypoint(resolvedCommand.toArray(String[]::new)));
            }
            cont.withCommand("/tmp/script");
        } else {
            if (!resolvedCommand.isEmpty()) {
                cont.withCommand(resolvedCommand.toArray(String[]::new));
            }
        }

        // Join the kwok Docker network so the container can reach the API server by hostname
        if (ctx.dockerNetwork != null) {
            cont.withNetwork(ctx.dockerNetwork);
        }

        // Inject a kubeconfig pointing to kwok — picked up by kubectl, fabric8, and other k8s clients
        if (ctx.podKubeconfig != null) {
            Path kubeconfigFile = Files.createTempFile(ctx.tmpDir, "kwok-kubeconfig-", ".yaml");
            Files.writeString(kubeconfigFile, ctx.podKubeconfig);
            cont.withCopyFileToContainer(MountableFile.forHostPath(kubeconfigFile), "/tmp/kwok-kubeconfig.yaml");
            cont.withEnv("KUBECONFIG", "/tmp/kwok-kubeconfig.yaml");
        }

        // Inject env vars (plain values and ConfigMap/Secret-resolved values)
        resolvedEnv.forEach(cont::withEnv);

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
            } else if (execProbeCommand != null && !execProbeCommand.isEmpty()) {
                int initialDelay = readinessProbe.getInitialDelaySeconds() != null
                        ? readinessProbe.getInitialDelaySeconds() : 0;
                int period = readinessProbe.getPeriodSeconds() != null
                        ? readinessProbe.getPeriodSeconds() : 10;
                int failureThreshold = readinessProbe.getFailureThreshold() != null
                        ? readinessProbe.getFailureThreshold() : 3;
                Duration probeTimeout = Duration.ofSeconds(initialDelay + (long) failureThreshold * period);
                String[] probeCmd = execProbeCommand.toArray(String[]::new);
                cont.waitingFor(new AbstractWaitStrategy() {
                    @Override
                    protected void waitUntilReady() {
                        if (initialDelay > 0) {
                            try { Thread.sleep(initialDelay * 1000L); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                        Instant deadline = Instant.now().plus(probeTimeout);
                        while (Instant.now().isBefore(deadline)) {
                            try {
                                var result = cont.execInContainer(probeCmd);
                                if (result.getExitCode() == 0) return;
                            } catch (Exception e) {
                                log.debug("Daemon pod '{}': exec probe error: {}", name, e.getMessage());
                            }
                            try { Thread.sleep(period * 1000L); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                        throw new IllegalStateException(
                                "Daemon pod '" + name + "': exec probe timed out after " + probeTimeout);
                    }
                });
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

        // Inject input artifacts before start, applying declared file mode if present
        for (ArtifactSpec decl : inputArtifactDecls) {
            Path hostPath = effectiveInputs.get(decl.name());
            if (hostPath != null) {
                log.debug("Pod '{}': injecting input artifact '{}' at '{}' mode={}",
                        name, decl.name(), decl.path(), decl.mode());
                MountableFile mf = decl.mode() != null
                        ? MountableFile.forHostPath(hostPath, decl.mode())
                        : MountableFile.forHostPath(hostPath);
                cont.withCopyFileToContainer(mf, decl.path());
            }
        }

        // Bind-mount volumes
        Map<String, IoK8sApiCoreV1Volume> vols = effectiveVolumes(ctx);
        for (VolumeMountSpec mount : volumeMountSpecs) {
            IoK8sApiCoreV1Volume vol = vols.get(mount.volumeName());
            if (vol == null) {
                log.warn("Pod '{}': volume '{}' not found in workflow or template spec, skipping mount", name, mount.volumeName());
                continue;
            }
            Path hostDir;
            if (vol.getEmptyDir() != null) {
                hostDir = Files.createTempDirectory(ctx.tmpDir, "vol-" + mount.volumeName() + "-");
            } else {
                hostDir = materializedVolumes.get(mount.volumeName());
                if (hostDir == null) continue;
            }
            Path mountSrc = mount.subPath() != null ? hostDir.resolve(mount.subPath()) : hostDir;
            BindMode mode = mount.readOnly() ? BindMode.READ_ONLY : BindMode.READ_WRITE;
            cont.withFileSystemBind(mountSrc.toString(), mount.mountPath(), mode);
            log.debug("Pod '{}': volume '{}' bound '{}' → '{}'", name, mount.volumeName(), mountSrc, mount.mountPath());
        }

        this.status = Status.RUNNING;
        Instant start = Instant.now();
        cont.start();
        String rawId = cont.getContainerId();
        String shortId = rawId != null ? rawId.substring(0, Math.min(12, rawId.length())) : null;
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
            podAttempts.add(new Attempt(shortId, elapsed, true, false, 0));
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
            podAttempts.add(new Attempt(shortId, elapsed, code == 0, false, code));
        }
    }

    /** spec.volumes merged with template-level volumes; template-level entries take precedence. */
    private Map<String, IoK8sApiCoreV1Volume> effectiveVolumes(ExecutionContext ctx) {
        if (templateVolumes.isEmpty()) return ctx.volumes;
        Map<String, IoK8sApiCoreV1Volume> merged = new LinkedHashMap<>(ctx.volumes);
        merged.putAll(templateVolumes);
        return merged;
    }

    private Map<String, Path> materializeVolumes(ExecutionContext ctx, Map<String, String> inputParams)
            throws Exception {
        if (volumeMountSpecs.isEmpty()) return Map.of();
        Map<String, IoK8sApiCoreV1Volume> vols = effectiveVolumes(ctx);
        if (vols.isEmpty()) return Map.of();
        Map<String, Path> result = new LinkedHashMap<>();
        for (VolumeMountSpec mount : volumeMountSpecs) {
            if (result.containsKey(mount.volumeName())) continue;
            IoK8sApiCoreV1Volume vol = vols.get(mount.volumeName());
            if (vol == null || vol.getEmptyDir() != null) continue; // emptyDir is created fresh per attempt
            Path hostDir = Files.createTempDirectory(ctx.tmpDir, "vol-" + mount.volumeName() + "-");
            if (vol.getConfigMap() != null) {
                String cmName = ctx.substitute(vol.getConfigMap().getName(), inputParams);
                populateFromConfigMap(hostDir, cmName, vol.getConfigMap().getItems(), ctx);
            } else if (vol.getSecret() != null) {
                String secretName = ctx.substitute(vol.getSecret().getSecretName(), inputParams);
                populateFromSecret(hostDir, secretName, vol.getSecret().getItems(), ctx);
            } else {
                log.warn("Pod '{}': unsupported volume type for '{}', skipping", name, mount.volumeName());
                continue;
            }
            result.put(mount.volumeName(), hostDir);
        }
        return Map.copyOf(result);
    }

    private void populateFromConfigMap(Path hostDir, String cmName,
                                       List<IoK8sApiCoreV1KeyToPath> items, ExecutionContext ctx)
            throws Exception {
        if (ctx.k8sClient == null) throw new IllegalStateException(
                "configMap volume requires a Kubernetes client — call withKwok() or getKubernetesClient()");
        var cm = ctx.k8sClient.configMaps().inNamespace(ctx.namespace).withName(cmName).get();
        if (cm == null) throw new IllegalStateException(
                "ConfigMap '" + cmName + "' not found in namespace '" + ctx.namespace + "'");
        Map<String, String> data = cm.getData() != null ? cm.getData() : Map.of();
        writeVolumeStringFiles(hostDir, data, items);
    }

    private void populateFromSecret(Path hostDir, String secretName,
                                    List<IoK8sApiCoreV1KeyToPath> items, ExecutionContext ctx)
            throws Exception {
        if (ctx.k8sClient == null) throw new IllegalStateException(
                "secret volume requires a Kubernetes client — call withKwok() or getKubernetesClient()");
        var secret = ctx.k8sClient.secrets().inNamespace(ctx.namespace).withName(secretName).get();
        if (secret == null) throw new IllegalStateException(
                "Secret '" + secretName + "' not found in namespace '" + ctx.namespace + "'");
        if (items != null && !items.isEmpty()) {
            Map<String, byte[]> decoded = new LinkedHashMap<>();
            if (secret.getData() != null) {
                for (var e : secret.getData().entrySet())
                    decoded.put(e.getKey(), Base64.getDecoder().decode(e.getValue()));
            }
            writeVolumeBinaryFiles(hostDir, decoded, items);
        } else {
            if (secret.getData() != null) {
                for (var e : secret.getData().entrySet()) {
                    Files.write(hostDir.resolve(e.getKey()), Base64.getDecoder().decode(e.getValue()));
                }
            }
        }
    }

    private static void writeVolumeStringFiles(Path hostDir, Map<String, String> data,
                                               List<IoK8sApiCoreV1KeyToPath> items) throws Exception {
        if (items != null && !items.isEmpty()) {
            for (var item : items) {
                String value = data.get(item.getKey());
                if (value == null) continue;
                Path target = hostDir.resolve(item.getPath()).normalize();
                if (!target.startsWith(hostDir))
                    throw new IllegalStateException("Volume item path traversal: " + item.getPath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, value);
            }
        } else {
            for (var e : data.entrySet()) Files.writeString(hostDir.resolve(e.getKey()), e.getValue());
        }
    }

    private static void writeVolumeBinaryFiles(Path hostDir, Map<String, byte[]> data,
                                               List<IoK8sApiCoreV1KeyToPath> items) throws Exception {
        for (var item : items) {
            byte[] value = data.get(item.getKey());
            if (value == null) continue;
            Path target = hostDir.resolve(item.getPath()).normalize();
            if (!target.startsWith(hostDir))
                throw new IllegalStateException("Secret volume item path traversal: " + item.getPath());
            Files.createDirectories(target.getParent());
            Files.write(target, value);
        }
    }

    private String readFileFromContainer(GenericContainer<?> cont, String containerPath) throws Exception {
        try (InputStream tarStream = cont.getDockerClient()
                     .copyArchiveFromContainerCmd(cont.getContainerId(), containerPath).exec();
             TarArchiveInputStream tarInput = new TarArchiveInputStream(tarStream)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (!entry.isDirectory()) {
                    return new String(tarInput.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
            }
        }
        throw new IllegalStateException("No file content at '" + containerPath + "'");
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
    /** Per-attempt execution records, in run order. Empty for skipped/omitted/pending pods. */
    public List<Attempt> podAttempts()       { return List.copyOf(podAttempts); }
    public Map<String, Path> collectedArtifacts()      { return collectedArtifacts; }
    public Map<String, String> collectedOutputParams() { return collectedOutputParams; }
    /** The stopped container. Logs and state remain accessible until Ryuk removes it. */
    public GenericContainer<?> container() { return container; }
    public Duration duration()             { return duration; }
}
