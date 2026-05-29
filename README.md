# argo-junit

A JUnit 5 extension that runs Argo Workflows locally against real containers (via
Testcontainers), without a Kubernetes cluster. Write tests that execute your workflows
and assert on their outcomes, step outputs, and artifacts.

## Motivation

Testing Argo Workflows in CI today requires either a real cluster, k3s/kind via Docker-in-Docker, or mocking Argo primitives — all slow, fragile, or both. `argo-junit` avoids all of these. Testcontainers talks to the host Docker daemon (no nesting). kwok provides a standards-compliant Kubernetes API server at negligible cost. The Java executor drives execution, running each step as a real container.

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

See [DESIGN.md](DESIGN.md) for the full API reference and architecture documentation.

## Running the tests

### Container runtime

Tests spin up real containers via Testcontainers. Docker must be running, or
Podman must be reachable via `DOCKER_HOST`:

```bash
DOCKER_HOST=unix:///run/user/1000/podman/podman.sock mvn test
```

Replace `/run/user/1000` with the value of `$XDG_RUNTIME_DIR` on your machine.

### Argo lint tests

`ArgoLintTest` validates every custom workflow fixture against `argo lint --offline`.
It requires the `argo` CLI to be on `PATH`. Install it from the
[Argo Workflows releases page](https://github.com/argoproj/argo-workflows/releases)
and ensure `argo version` works before running tests.

To skip the lint tests when the `argo` binary is unavailable:

```bash
mvn test -DskipArgoLint=true
```
