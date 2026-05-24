# argo-junit

## Container runtime

Tests spin up real containers via Testcontainers. If the machine uses Podman instead of Docker, `DOCKER_HOST` must point to the Podman socket before running `mvn test`:

```bash
export DOCKER_HOST=unix://${XDG_RUNTIME_DIR}/podman/podman.sock
```

If Docker is available and `DOCKER_HOST` is already set, no action is needed.

## Test resources

`src/test/resources/examples` is a symlink to the `argo-workflows` submodule. Never create or modify files inside it. Put custom test fixtures directly under `src/test/resources/` (e.g. `src/test/resources/my-fixture.yaml`).
