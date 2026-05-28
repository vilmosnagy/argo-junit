# argo-junit

A JUnit 5 extension that executes Argo Workflows locally via Testcontainers,
without a real Kubernetes cluster.

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
