# argo-junit Design Document

## Overview

`argo-junit` is a Java library for integration-testing Argo Workflows without a real Kubernetes cluster or Docker-in-Docker. It reimplements a supported subset of the Argo Workflows controller in Java, runs workflow step containers via Testcontainers against the host Docker daemon, and uses [kwok](https://kwok.sigs.k8s.io/) as a lightweight fake Kubernetes API for anything the workflow or its containers need (ConfigMaps, Secrets, etc.).

## Motivation

Testing Argo Workflows in CI today requires either a real cluster, k3s/kind via Docker-in-Docker, or mocking Argo primitives — all slow, fragile, or both. `argo-junit` avoids all of these. Testcontainers talks to the host Docker daemon (no nesting). kwok provides a standards-compliant Kubernetes API server at negligible cost. The Java executor drives execution, running each step as a real container.

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

## Planned Feature Set

Features will be added incrementally per the build order below.

### In scope

| Feature | Notes |
|---|---|
| `container` templates | Full support |
| `script` templates | stdout → `outputs.result` |
| `steps` templates | Sequential groups, parallel within group |
| `dag` templates | Dependency-based execution |
| `inputs.parameters` / `outputs.parameters` | Full parameter passing |
| `outputs.result` | Script stdout capture |
| `when` conditionals | Evaluated after expression substitution |
| `retryStrategy` | Re-run container up to N times on non-zero exit |
| Inter-step artifact passing | Via host temp directory |
| Explicit artifact locations (`s3:`, `gcs:`, `azure:`) | ArtifactDriver impls; credentials from kwok Secrets |
| `WorkflowTemplate` resolution | Via kwok CRD lookup |

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
public class ArgoWorkflowExecutor {
    // All three from() variants eagerly parse the workflow YAML into a Workflow model object.
    public static ArgoWorkflowExecutor from(Path workflowFile);
    public static ArgoWorkflowExecutor from(String workflowYaml);
    public static ArgoWorkflowExecutor from(Workflow workflow);

    // Starts kwok if not already running and returns a fabric8 client pointed at it.
    // Call this before execute() when the test needs to pre-populate Secrets, ConfigMaps, etc.
    public KubernetesClient getKubernetesClient();   // planned

    // Non-blocking: launches execution on a daemon thread pool, returns immediately.
    public WorkflowRun executeAsync() throws Exception;

    // Blocking convenience wrapper: equivalent to executeAsync().await().
    public WorkflowRun execute() throws Exception;
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
    boolean skipped();
    boolean running();
    boolean pending();
    void skip();
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
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    public Status status();
    public int exitCode();
    public String logs();
    public Duration duration();

    // outputs.result — stdout of a script template. Empty for container templates.
    public Optional<String> outputResult();
    public Optional<String> outputParameter(String name);  // planned

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
public final class WorkflowRun {
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

**Template substitution** — regex-based replacement of `{{...}}` placeholders. Three patterns resolved in order: `{{steps.<n>.outputs.result}}` (from completed steps), `{{inputs.parameters.<name>}}` (from task/step arguments), `{{workflow.parameters.<name>}}` (from workflow-level arguments). Applied to image, command, args, script source, and `when` fields.

**`when` evaluation** — after substitution, `when` fields are plain comparison strings (e.g. `"heads == heads"`). Currently evaluated with simple string splitting on ` == ` and ` != `; boolean literals also accepted. Full expression support planned via Apache JEXL or SpEL. No custom parser.

Artifact references (`{{steps.X.outputs.artifacts.foo}}`) are not substituted as strings — the executor will resolve them as artifact handles on a separate code path (planned).

### Artifact Subsystem (`artifact/`)

**ArtifactDriver interface:**

```java
public interface ArtifactDriver {
    boolean supports(Artifact artifact);           // checks which location field is non-null
    void upload(Artifact artifact, Path localPath);  // file or directory
    void download(Artifact artifact, Path localPath);
}
```

`Artifact` is the generated POJO from the Argo model; the location fields (`s3`, `gcs`, `azure`) are inline on it. `supports()` simply checks which field is non-null.

Drivers are discovered via Java's built-in `ServiceLoader<ArtifactDriver>`. Built-in implementations register themselves in `META-INF/services`; users can provide additional drivers on the classpath without any code-level registration. The executor picks the first driver whose `supports()` returns true.

When Argo's `archive` setting requires tar.gz, the executor handles pack/unpack on the host around the driver call — the driver itself just transfers whatever local path it receives (file or directory). Credentials are resolved from kwok Secrets.

Built-in implementations: `S3ArtifactDriver`, `GcsArtifactDriver`, `AzureBlobArtifactDriver`.

**Inter-step artifact passing** — output artifacts with no explicit location, and inputs using `from:`, are routed through a temporary directory created via `Files.createTempDirectory()`. No external store involved.

### Executor (`executor/`)

For each leaf step:
1. Substitute all `{{...}}` in image, command, args, env
2. Evaluate `when` — skip if false
3. Download input artifacts (driver or temp dir copy)
4. Build Testcontainer: inject KUBECONFIG, mount named volumes at **parent** of each output artifact path, set env vars
5. Start, wait for exit; apply `retryStrategy` on failure
6. Capture stdout → `outputs.result`
7. Collect output artifacts from volumes; upload (driver) or copy (temp dir) per artifact type
8. Update `ExecutionContext`

Returns a `WorkflowRun` with per-step outcomes, outputs, and logs.

### kwok Integration (`kwok/`)

kwok runs as a Testcontainer on a shared Docker network with step containers. The executor uses the fabric8 client pointed at kwok to resolve `secretKeyRef` credentials for artifact drivers and (later) fetch `WorkflowTemplate` CRDs. Test setup applies Secrets and WorkflowTemplates to kwok before running the workflow. Step containers receive `KUBECONFIG` pointing to kwok's in-network address.

## Artifact — Directory Output Edge Case

`copyFileFromContainer` in Testcontainers does not support directories. Rather than detecting this at runtime (after the container has exited, too late to remount), the executor **always** mounts a named Docker volume at the **parent** of every declared output artifact path, preemptively. After the container exits, a short-lived utility container mounts the same volume, and the executor copies the artifact path out from there.

Known accepted limitations: files already at the mount parent inside the image are shadowed; multiple artifact paths sharing a parent share a volume.

## Testing Philosophy

- **No mocking frameworks.** Tests run against real containers and a real (fake) Kubernetes API.
- **Unit tests only for isolated utilities.** The expression substitution and artifact archive logic are candidates. Everything else is an integration test.
- **Hamcrest assertions** (`assertThat` + `Matchers.*`) throughout. `Assert.*` from JUnit is avoided.

## Test Resources — Argo Workflows Submodule

`argo-workflows` is added as a git submodule at the repo root. Example workflows are exposed to tests via a symlink:

```
src/test/resources/examples → ../../argo-workflows/examples
```

## Project Structure

```
argo-junit/
├── argo-workflows/               ← git submodule
├── src/
│   ├── main/java/eu/vnagy/argotools/
│   │   ├── model/                ← generated POJOs
│   │   ├── expression/           ← substitution + when evaluator
│   │   ├── artifact/             ← ArtifactDriver interface + impls
│   │   ├── executor/             ← execution engine, Testcontainer lifecycle
│   │   ├── util/                 ← WorkflowSummary and other test utilities
│   │   └── kwok/                 ← kwok Testcontainer setup, fabric8 wiring
│   └── test/
│       ├── java/eu/vnagy/argotools/
│       └── resources/
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

### Phase 3+ — To be defined

Candidates: artifact passing, kwok integration, `retryStrategy`, `WorkflowTemplate` resolution.
