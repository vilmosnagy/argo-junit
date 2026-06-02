# argo-junit

A JUnit 5 library that executes Argo Workflows locally against real containers,
without a Kubernetes cluster. Write tests that run your actual workflows and assert
on their outcomes, step outputs, and artifacts.

## Motivation

Testing Argo Workflows in CI today requires either a real cluster, k3s/kind via Docker-in-Docker, or mocking Argo primitives — all slow, fragile, or both. `argo-junit` avoids all of these. Testcontainers talks to the host Docker daemon (no nesting). kwok provides a standards-compliant Kubernetes API server at negligible cost. The Java executor drives execution, running each step as a real container.

## What it supports

- `script` and `container` templates — real containers, real stdout, real exit codes
- `steps` (sequential groups, parallel within group) and `dag` templates with `depends:`/`dependencies:`
- `daemon` templates — IP exposed to downstream steps, stopped after scope completes
- Inter-step artifact passing (files and directories); `S3`/MinIO external artifacts with tar.gz archive handling
- `when` conditionals, `retryStrategy` with exponential backoff
- `outputs.parameters` (file-backed, expression-based), `outputs.result`, `outputs.artifacts`
- `WorkflowTemplate` resolution via a lightweight fake Kubernetes API (kwok)
- `volumes` + `volumeMounts` (`emptyDir`, `configMap`, `secret`)
- `env[]` from `configMapKeyRef` / `secretKeyRef`

Full feature table and API reference: [DESIGN.md](DESIGN.md)

## Installation

> **Note:** argo-junit is not yet published to Maven Central. Build from source:

```bash
git clone --recurse-submodules https://github.com/vilmosnagy/argo-junit.git
cd argo-junit
mvn install -DskipTests
```

Then add the dependency to your project (Java 25+ required):

```xml
<dependency>
    <groupId>eu.vnagy.argotools</groupId>
    <artifactId>argo-junit</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

## Quick start

```java
// Simple workflow — no Kubernetes API needed
try (WorkflowRun run = ArgoWorkflowExecutor
        .from(Path.of("src/test/resources/my-workflow.yaml"))
        .execute()) {
    assertThat(run.succeeded(), is(true));
}
```

```java
// Workflow that uses ConfigMaps, Secrets, or WorkflowTemplates — start kwok first
try (var executor = ArgoWorkflowExecutor.from(Path.of("my-workflow.yaml"))) {
    executor.getKubernetesClient()
            .configMaps().inNamespace("default")
            .resource(myConfigMap).create();
    try (WorkflowRun run = executor.execute()) {
        assertThat(run.succeeded(), is(true));
        PodRun step = (PodRun) ((StepsRun) run.entrypoint()).get("my-step");
        assertThat(step.outputResult(), is(Optional.of("expected")));
    }
}
```

```java
// Share one kwok instance across test methods to amortise the startup cost
static KwokContainer kwok;

@BeforeAll static void setup() { kwok = new KwokContainer(); kwok.start(); }
@AfterAll  static void tearDown() { kwok.stop(); }

@Test void myTest() throws Exception {
    try (WorkflowRun run = ArgoWorkflowExecutor.from(path).withKwok(kwok).execute()) {
        assertThat(run.succeeded(), is(true));
    }
}
```

## Contributing

### Container runtime

Tests spin up real containers via Testcontainers. Docker must be running, or
Podman must be reachable via `DOCKER_HOST`:

```bash
DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock mvn test
```

### Argo lint tests

`ArgoLintTest` validates every custom workflow fixture against `argo lint --offline`.
It requires the `argo` CLI to be on `PATH`. Install it from the
[Argo Workflows releases page](https://github.com/argoproj/argo-workflows/releases)
and ensure `argo version` works before running tests.

To skip the lint tests when the `argo` binary is unavailable:

```bash
mvn test -DskipArgoLint=true
```

## License

Apache License 2.0 — see [LICENSE.txt](LICENSE.txt).
