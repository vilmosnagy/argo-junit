# argo-junit Design Document

## Overview

`argo-junit` is a Java library for integration-testing Argo Workflows without a real Kubernetes cluster or Docker-in-Docker. It reimplements a supported subset of the Argo Workflows controller in Java, runs workflow step containers via Testcontainers against the host Docker daemon, and uses [kwok](https://kwok.sigs.k8s.io/) as a lightweight fake Kubernetes API for anything the workflow or its containers need (ConfigMaps, Secrets, etc.).

## Architecture

```
Test class
  │  calls ArgoWorkflowExecutor.from(path)
  │  optionally calls executor.getKubernetesClient() → applies Secrets/ConfigMaps to kwok
  │  calls executor.execute()
  ▼
ArgoWorkflowExecutor (Java)
  │  parses Workflow YAML → model POJOs
  │  resolves templates (inline or from WorkflowTemplate via kwok)
  │  walks DAG / steps, evaluates expressions and `when` conditions
  │
  ├─► kwok (Testcontainer, started lazily on first call to getKubernetesClient()
  │         or by execute() if artifact credentials / KUBECONFIG injection are needed)
  │     Kubernetes API (fake nodes, no kubelet)
  │     Stores: ConfigMaps, Secrets, WorkflowTemplates (CRDs)
  │     Used by: executor (credential/template resolution) + step containers
  │
  ├─► Step container (Testcontainer, per workflow pod)
  │     Real image, real command/script
  │     KUBECONFIG injected → talks to kwok
  │     Named volumes mounted at parent of each output artifact path
  │     stdout captured → outputs.result
  │
  └─► ArtifactDriver (per artifact location type)
        Reads credentials from kwok Secrets
        Downloads input artifacts into step container before start
        Uploads output artifacts from step container after exit
        Inter-step passing via host temp directory (no external store)
```

## Feature Set

| Feature | Status | Notes |
|---|---|---|
| `container` templates | ✓ | Full support |
| `script` templates | ✓ | stdout → `outputs.result` |
| `steps` templates | ✓ | Sequential groups, parallel within group |
| `dag` templates | ✓ | Dependency-based execution with `depends:` expressions |
| `daemon` templates | ✓ | Container left running; IP exposed to downstream steps; stopped after its scope completes; HTTP and exec `readinessProbe` supported |
| `inputs.parameters` / `outputs.parameters` | ✓ | Full parameter passing |
| `outputs.result` | ✓ | Script stdout capture |
| `outputs.parameters[].valueFrom.path` | ✓ | File content read from container after exit |
| `inputs.parameters[].valueFrom.configMapKeyRef` | ✓ | Template-level parameter from ConfigMap |
| `when` conditionals | ✓ | Evaluated after expression substitution |
| `retryStrategy` | ✓ | `limit`, `retryPolicy` (OnFailure / OnError / Always), exponential backoff with `factor`, `cap`, `maxDuration` |
| `spec.templateDefaults.retryStrategy` | ✓ | Fallback retry strategy for templates without their own |
| `env[].valueFrom.configMapKeyRef` / `secretKeyRef` | ✓ | Container/script env vars resolved from ConfigMap or Secret |
| `volumes` + `volumeMounts` | ✓ | `emptyDir`, `configMap`, `secret` (with `items` filtering); bind-mounted onto containers |
| Inter-step artifact passing | ✓ | Files and directories; lazy extraction — only artifacts consumed downstream are collected |
| Workflow-level HTTP input artifacts | ✓ | `arguments.artifacts[].http.url` downloaded before execution starts |
| Explicit S3 artifact locations (`s3:`) | ✓ | `S3ArtifactDriver`; credentials from kwok Secrets; tar.gz archive handling; MinIO-compatible |
| Explicit GCS / Azure artifact locations | — | `ArtifactDriver` interface ready; no built-in implementations yet |
| `WorkflowTemplate` resolution | ✓ | Via kwok CRD lookup; `templateRef` in steps and DAG tasks |

### Out of scope

| Feature | Reason |
|---|---|
| `withItems` / `withParam` | Not needed in current workflows |
| `suspend` templates | Not used |
| `resource` templates | Not needed yet |
| Concurrency limits | Fields parsed, not enforced |

## Java API

### Entry points

```java
public class ArgoWorkflowExecutor implements AutoCloseable {
    // All three from() variants eagerly parse the workflow YAML into a Workflow model object.
    public static ArgoWorkflowExecutor from(Path workflowFile);
    public static ArgoWorkflowExecutor from(String workflowYaml);
    public static ArgoWorkflowExecutor from(Workflow workflow);

    // Sets the Kubernetes namespace for ConfigMap/Secret lookups (default: "default").
    public ArgoWorkflowExecutor withNamespace(String namespace);

    // Supplies an externally-managed kwok container. Does not start or stop it.
    // Use to share a single kwok instance across multiple executors or test methods.
    public ArgoWorkflowExecutor withKwok(KwokContainer kwok);

    // Supplies an externally-managed Docker network. Not closed by close().
    public ArgoWorkflowExecutor withNetwork(Network network);

    // Starts an executor-owned kwok cluster and returns a fabric8 client pointed at it.
    // Call this before execute() when the workflow uses ConfigMaps, Secrets, or WorkflowTemplates.
    // The cluster is stopped when close() is called.
    public KubernetesClient getKubernetesClient();

    // Non-blocking: launches execution on a daemon thread pool, returns immediately.
    public WorkflowRun executeAsync();

    // Blocking convenience wrapper: equivalent to executeAsync().await().
    public WorkflowRun execute() throws Exception;

    // Stops the executor-owned kwok container (if any) and closes its Docker network.
    // Resources supplied via withKwok() or withNetwork() are never touched.
    @Override void close();
}
```

`from()` parses the workflow immediately. `executeAsync()` resolves templates, extracts workflow-level parameters, builds the full workflow tree (stopping at recursion boundaries), and launches execution. It returns a `WorkflowRun` before any task has started — the same object reflects live state throughout execution. `execute()` is a thin synchronous wrapper over `executeAsync().await()`.

### WorkflowNode hierarchy

`WorkflowNode` is a sealed interface — the common ancestor for everything the executor produces. Java 25 sealed types allow exhaustive `switch` pattern matching over the four permitted subtypes.

```java
public sealed interface WorkflowNode permits DagRun, StepsRun, PodRun, UninitializedNode {
    String name();
    boolean succeeded();
    boolean failed();
    boolean errored();
    boolean skipped();
    boolean omitted();
    boolean daemoned();
    boolean running();
    boolean pending();
    void skip();
    void omit();
    // Current (final) attempt's direct child nodes; empty for leaf nodes.
    List<WorkflowNode> children();
    // Per-attempt child-node maps from failed retries before the final one, in order.
    List<Map<String, WorkflowNode>> attemptHistory();
    // Total completed attempts (0 = not yet run, 1 = ran once, N = retried N-1 times).
    int attempts();
}
```

`DagRun` and `StepsRun` aggregate child state: `running()` = any child running; `pending()` = all children pending.

```java
public final class DagRun implements WorkflowNode {
    // Looks up a task by name. Throws if not found.
    public WorkflowNode get(String taskName);
    public Collection<WorkflowNode> tasks();
}
```

```java
public final class StepsRun implements WorkflowNode {
    // Looks up a step by name. Throws if not found.
    public WorkflowNode get(String stepName);
    public Collection<WorkflowNode> steps();
}
```

```java
public final class PodRun implements WorkflowNode {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, ERRORED, SKIPPED, OMITTED, DAEMONED }

    // Per-attempt execution record populated after each container run completes.
    public record Attempt(String containerId, Duration duration, boolean succeeded, boolean errored, int exitCode) {}

    public Status status();
    public int exitCode();
    public String logs();
    public Duration duration();
    public int attempts();  // total container starts across all retry attempts
    // Per-attempt execution records, in run order.
    public List<Attempt> podAttempts();

    // outputs.result — stdout of a script template. Empty for container templates.
    public Optional<String> outputResult();

    // outputs.parameters collected after a successful run, keyed by parameter name.
    public Map<String, String> collectedOutputParams();

    // Daemon pods only.
    public Optional<String> ip();
    public boolean isDaemonStopped();

    // Artifacts collected from this pod's outputs, keyed by artifact name.
    public Map<String, Path> collectedArtifacts();

    // Stopped by the time WorkflowRun is returned, but getLogs() etc. remain accessible.
    public GenericContainer<?> container();
}
```

`PodRun` is constructed at tree-build time with `PENDING` status. All mutable fields (`status`, `exitCode`, `logs`, etc.) are `volatile` and updated in-place as the pod runs — no new instance is created.

```java
public final class UninitializedNode implements WorkflowNode {
    // Non-null once the node has been expanded by executeAsync().
    public WorkflowNode resolved();
}
```

`UninitializedNode` marks a recursion boundary — a point where the template is known but the subtree cannot be built without infinite recursion (e.g. a template that calls itself). It reports `pending()` until `executeAsync()` is called, at which point it builds one more level of the tree, sets `resolved`, and delegates execution. `WorkflowSummary` and other tree walkers follow `resolved()` transparently.

### WorkflowRun

```java
public final class WorkflowRun implements AutoCloseable {
    boolean isDone();
    boolean succeeded();
    boolean failed();
    boolean running();
    boolean pending();

    // Blocks until the workflow completes and returns this.
    WorkflowRun await() throws Exception;

    // Returns the top-level node (the entrypoint template). 
    // Navigate into it via DagRun/StepsRun.get() for specific steps/tasks.
    WorkflowNode entrypoint();

    // Finds a PodRun by the short container ID (first 12 hex chars of the Docker container ID).
    // Searches across retry attempts. Returns empty if no matching container ran in this workflow.
    Optional<PodRun> findByContainerId(String shortId);

    // Deletes the per-run artifact temp directory. Use via try-with-resources.
    @Override void close();
}
```

`WorkflowRun` holds the live tree directly — the same `WorkflowNode` instances are mutated in-place as execution progresses. `executeAsync()` returns the `WorkflowRun` immediately; callers can observe live state by reading the tree at any time. Flat lookup by step name is not supported; navigate the hierarchy explicitly.

### Example usage

```java
// Simple workflow — no k8s API needed, kwok never starts
WorkflowRun run = ArgoWorkflowExecutor
    .from(Path.of("src/test/resources/examples/coinflip.yaml"))
    .execute();
```

```java
// Workflow whose artifact credentials live in a k8s Secret:
// the test must apply the Secret to kwok before execute() tries to resolve it.
//
// outputs:
//   artifacts:
//   - name: result
//     s3:
//       bucket: my-bucket
//       accessKeySecret:
//         name: minio-creds
//         key: accessKey
//
var executor = ArgoWorkflowExecutor.from(Path.of("my-workflow.yaml"));
executor.getKubernetesClient()   // starts kwok
    .secrets()
    .inNamespace("default")
    .create(minioCredsSecret);
WorkflowRun run = executor.execute();

assertThat(run.succeeded(), is(true));

// coinflip entrypoint is a steps template
StepsRun coinflip = (StepsRun) run.entrypoint();
PodRun flipCoin = (PodRun) coinflip.get("flip-coin");
assertThat(flipCoin.outputResult(), anyOf(is(Optional.of("heads")), is(Optional.of("tails"))));

// pattern matching for unknown node type
switch (coinflip.get("heads")) {
    case PodRun pod              -> assertThat(pod.skipped(), is(false));
    case DagRun dag              -> fail("unexpected dag");
    case StepsRun steps          -> fail("unexpected steps");
    case UninitializedNode unin  -> fail("unexpected uninitialized node");
}
```

## Components

### Model (`model/`)

POJOs generated from the Argo Workflows OpenAPI spec (`argo-workflows/api/openapi-spec/swagger.json`) via `openapi-generator-maven-plugin`. A preprocessing step (exec-maven-plugin + `sed`) strips the `io.argoproj.workflow.v1alpha1.` prefix from all definition keys before generation, giving clean class names (`Workflow`, `WorkflowSpec`, `Template`, `ScriptTemplate`, etc.) for Argo types while Kubernetes types retain their verbose prefix-derived names (`IoK8sApiCoreV1Container`, etc.) to avoid name collisions. Jackson annotations for YAML/JSON deserialization. No external Kubernetes client dependency.

### Expression Engine (`expression/`)

Two responsibilities, currently implemented inline in the executor:

**Template substitution** — regex-based replacement of `{{...}}` placeholders. Patterns resolved in order:
- `{{steps.<n>.outputs.result}}` — output result of a preceding step
- `{{steps.<n>.ip}}` / `{{tasks.<n>.ip}}` — IP address of a daemon pod
- `{{steps.<n>.outputs.parameters.<name>}}` / `{{tasks.<n>.outputs.parameters.<name>}}` — file-backed output parameter
- `{{inputs.parameters.<name>}}` — parameter passed to this template invocation
- `{{workflow.parameters.<name>}}` — workflow-level argument

Applied to: image, command, args, script source, `when` fields, and ConfigMap/Secret resource name and key references.

**`when` evaluation** — after substitution, `when` fields are plain comparison strings (e.g. `"heads == heads"`). Currently evaluated with simple string splitting on ` == ` and ` != `; boolean literals also accepted. Full expression support planned via Apache JEXL or SpEL. No custom parser.

Artifact references (`{{steps.X.outputs.artifacts.foo}}`) are not substituted as strings — the executor resolves them as artifact handles on a separate code path: `DagRun`/`StepsRun` parse `from:` expressions at plan time to determine which upstream artifacts to collect, and pass the resolved host paths to downstream pods via `inputArtifacts`.

### Artifact Subsystem (`artifact/`)

**ArtifactDriver interface:**

```java
public interface ArtifactDriver {
    boolean supports(Artifact artifact);  // checks which location field is non-null
    // Download artifact to tempDir; returns host-side path of the resulting file or directory.
    Path download(Artifact artifact, Path tempDir, KubernetesClient k8sClient, String namespace) throws Exception;
    // Upload source (file or directory) to the artifact's external location.
    void upload(Artifact artifact, Path source, KubernetesClient k8sClient, String namespace) throws Exception;
}
```

`Artifact` is the generated POJO from the Argo model; the location fields (`s3`, `gcs`, `azure`) are inline on it. `supports()` simply checks which field is non-null.

Drivers are discovered via Java's built-in `ServiceLoader<ArtifactDriver>`. Built-in implementations register themselves in `META-INF/services`; users can provide additional drivers on the classpath without any code-level registration. The executor picks the first driver whose `supports()` returns true.

The driver is responsible for tar.gz compression/decompression (following Argo's default archive strategy) unless `archive.none: {}` is set on the artifact. Credentials are resolved from kwok Secrets by the driver via the supplied `k8sClient`.

Built-in implementations: `S3ArtifactDriver` (S3-compatible; MinIO/AWS; tar.gz archive handling). GCS and Azure implementations are not yet provided.

**Inter-step artifact passing** — output artifacts with no explicit location, and inputs using `from:`, are routed through a temporary directory created via `Files.createTempDirectory()`. No external store involved.

### Executor (`executor/`)

For each leaf step:
1. Substitute all `{{...}}` in image, command, args, script source
2. Evaluate `when` — skip if false
3. Inject input artifacts — copy from host temp directory into the container before start
4. Build and start Testcontainer; write script source to `/tmp/script` if a script template
5. Start, wait for exit; apply `retryStrategy` (retry loop with optional exponential backoff)
6. Capture stdout → `outputs.result` (script templates only)
7. Collect output artifacts via Docker TAR API into the per-run temp directory
8. Register results in `ExecutionContext` (output results, IPs, artifact paths)

Returns a `WorkflowRun` with per-step outcomes, outputs, and logs.

### kwok Integration (`kwok/`)

kwok runs as a Testcontainer on a shared Docker network with step containers. The executor uses the fabric8 client pointed at kwok to resolve `configMapKeyRef`/`secretKeyRef` values, fetch `WorkflowTemplate` CRDs, and supply credentials to artifact drivers. Test setup applies Secrets, ConfigMaps, and WorkflowTemplates to kwok before running the workflow. Step containers receive `KUBECONFIG` pointing to kwok's in-network address.

The executor can own kwok (started by `getKubernetesClient()`, stopped by `close()`) or borrow an externally-managed instance via `withKwok(KwokContainer)`. Borrowed containers are never stopped by the executor — this allows sharing a single kwok across multiple executors or test methods, amortising the ~1–2 s startup cost.

`ArgoKwok` (in `eu.vnagy.argotools.junit.kwok`) wraps `KwokContainer` and installs the Argo Workflows CRDs on startup via the quick-start manifest bundled in the library jar. Use it whenever a test needs to register `WorkflowTemplate` resources or run workflows that use `templateRef`. `ArgoKwok.container()` returns the underlying `KwokContainer` for passing to `withKwok()`.

## Artifact Collection

`copyFileFromContainer` in Testcontainers only reads the first TAR entry and does not support directories. Instead, the executor uses the raw Docker TAR API — `copyArchiveFromContainerCmd` — which returns the full archive stream for any path, file or directory. `commons-compress` extracts the TAR into a per-run temp directory under `ExecutionContext.tmpDir`, and the top-level extracted entry becomes the artifact path.

Artifacts are collected lazily: `DagRun` and `StepsRun` precompute at plan time which of each task's output artifacts are consumed by downstream `from:` references. Only those are passed to the pod as `requestedOutputArtifacts`; unclaimed artifacts are never extracted from the container.

`WorkflowRun` implements `AutoCloseable`; `close()` deletes `tmpDir` and everything under it.

## Testing Philosophy

- **No mocking frameworks.** Tests run against real containers and a real (fake) Kubernetes API.
- **Unit tests only for isolated utilities.** The expression substitution and artifact archive logic are candidates. Everything else is an integration test.
- **Hamcrest assertions** (`assertThat` + `Matchers.*`) throughout. `Assert.*` from JUnit is avoided.

### Test package convention

Two top-level test packages serve distinct purposes and must not be mixed:

| Package | Purpose |
|---|---|
| `io.github.argoproj.argoworkflows` | Compatibility tests for upstream Argo example workflows from the submodule (`src/test/resources/examples`). The package name mirrors the Argo Workflows GitHub org. Tests here assert only that the executor correctly runs a given upstream workflow — no bespoke helper infrastructure or custom YAML fixtures belong here. |
| `eu.vnagy.argotools.junit` | Feature-level integration tests for executor behaviour: gate-controlled retries, artifact lifecycle, scope isolation, daemon lifecycle, live-tree observation, summary formatting. Custom YAML fixtures go under `src/test/resources/` (not `examples/`). |

Two sub-packages have narrower roles:

| Sub-package | Purpose |
|---|---|
| `eu.vnagy.argotools.junit.executor` | Package-private executor tests that must reach into internals (e.g. `ArtifactLifecycleTest` accessing `WorkflowRun.tmpDir`). Only use this when package-private access is genuinely required. |
| `eu.vnagy.argotools.junit.testutil` | Shared test infrastructure: `WorkflowReleaseGate`, `RetryOutcomeGate`, `MinioContainer`. Not test classes — no `@Test` methods here. |

## Test Resources — Argo Workflows Submodule

`argo-workflows` is added as a git submodule at the repo root. Example workflows are exposed to tests via a symlink:

```
src/test/resources/examples → ../../argo-workflows/examples
```

## Project Structure

```
argo-junit/
├── argo-workflows/                     ← git submodule
├── src/
│   ├── main/java/eu/vnagy/argotools/junit/
│   │   ├── model/                      ← generated POJOs
│   │   ├── artifact/                   ← ArtifactDriver interface + S3 impl
│   │   ├── executor/                   ← execution engine, Testcontainer lifecycle
│   │   ├── util/                       ← WorkflowSummary and other utilities
│   │   └── kwok/                       ← KwokContainer, ArgoKwok
│   └── test/
│       ├── java/
│       │   ├── io/github/argoproj/argoworkflows/   ← upstream example workflow tests
│       │   └── eu/vnagy/argotools/junit/
│       │       ├── (feature tests)                 ← custom integration tests
│       │       ├── executor/                       ← package-private executor tests
│       │       ├── testutil/                       ← WorkflowReleaseGate, RetryOutcomeGate, MinioContainer
│       │       └── workflowtemplates/              ← WorkflowTemplate resolution tests
│       └── resources/
│           ├── (custom YAML fixtures)
│           └── examples → ../../argo-workflows/examples  (symlink)
├── DESIGN.md
└── pom.xml
```

## Build Order

### Phase 1 — PoC: `hello-world.yaml` + `coinflip.yaml` ✓ Done

Goal: end-to-end execution of the two simplest canonical workflows as fast as possible.

Requires: model (YAML parsing), expression substitution, `when` evaluation, `container` and `script` template execution, `steps` executor, Testcontainer lifecycle. No kwok, no artifact handling.

- `hello-world.yaml` — single container template, no parameters, no steps
- `coinflip.yaml` — script template, two-step group, `when` conditional, `outputs.result` passing

### Phase 2 — Async execution + DAG support ✓ Done

Goal: parallel DAG execution with observable in-progress state.

- `dag` template executor: topological sort (Kahn's algorithm), `CompletableFuture` chaining per task for true parallelism
- Unified live tree: `executeAsync()` returns `WorkflowRun` immediately; the same `WorkflowNode` instances are mutated in-place — no snapshot needed
- `UninitializedNode` as a recursion boundary: tree is pre-built eagerly at parse time, stopping at self-referencing template calls to avoid infinite construction; each boundary expands one level when its `executeAsync()` is called
- `running()` / `pending()` / `skip()` on all `WorkflowNode` types; `PodRun.Status` enum
- `{{workflow.parameters.X}}` substitution; script source substitution fixed; argument passing from `dag` tasks and `steps` to child templates
- Test: `LiveWorkflowRunTest` — embedded JDK HTTP server as a release trigger, deterministic observation of four simultaneous states (SUCCEEDED, RUNNING, PENDING, PENDING) mid-run
- Test: `CountdownLoopTest` — self-recursive `steps` template with a countdown counter; verifies that the tree expands level-by-level during execution and that `UninitializedNode.resolved()` is navigable after completion

### Phase 3 — Retries, daemons, and inter-step artifact passing ✓ Done

- `retryStrategy`: retry loop in `PodRun` with configurable limit, `retryPolicy` (OnFailure / OnError / Always), and exponential backoff (`factor`, `cap`, `maxDuration`); `PodRun.attempts()` exposes total container starts
- Daemon pod lifecycle: container left running after start, IP registered in `ExecutionContext`, stopped after its enclosing scope (steps group or DAG) completes
- Inter-step artifact passing: output artifacts collected from stopped containers via Docker TAR API into `ExecutionContext.tmpDir`; only artifacts consumed by downstream steps extracted (lazy); `WorkflowRun` implements `AutoCloseable` for cleanup
- `WorkflowSummary`: human-readable tree of step outcomes with duration and retry count

### Phase 4 — kwok integration, WorkflowTemplate resolution, S3 artifacts, volumes, env injection ✓ Done

Goal: bring the executor to parity with real-world workflows that use Kubernetes API objects.

- `kwok` integration: `KwokContainer` Testcontainer; `getKubernetesClient()`, `withKwok()`, `withNetwork()`, `withNamespace()` API; executor-owned vs. externally-managed lifecycle; `ArgoWorkflowExecutor` implements `AutoCloseable`
- `WorkflowTemplate` resolution via kwok CRD lookup; `templateRef` in steps and DAG tasks; `ArgoKwok` test helper installs Argo CRDs at startup
- `S3ArtifactDriver`: upload/download with tar.gz archive handling; credentials from kwok Secrets; MinIO/AWS-compatible
- Workflow-level HTTP input artifacts: `arguments.artifacts[].http.url` downloaded before execution
- `outputs.parameters[].valueFrom.path`: file content read from container after exit
- `inputs.parameters[].valueFrom.configMapKeyRef`: template-level parameter from ConfigMap
- `env[].valueFrom.configMapKeyRef` / `secretKeyRef`: container/script env vars resolved from ConfigMap or Secret
- `volumes` + `volumeMounts`: `emptyDir`, `configMap`, `secret` (with `items` filtering); bind-mounted onto step containers
- `spec.templateDefaults.retryStrategy`: fallback retry strategy for templates without their own
- `daemon` exec `readinessProbe`: polls via `execInContainer` until the probe command exits 0
- `PodRun.podAttempts()`: per-attempt execution records (container ID, duration, exit code)
- `WorkflowRun.findByContainerId()`: locate a `PodRun` by short container ID, including across retry attempts

### Phase 5+ — To be defined

Candidates: GCS / Azure artifact drivers, `withItems` / `withParam` loops.
