# argo-junit

## Container runtime

Tests spin up real containers via Testcontainers. If the machine uses Podman instead of Docker, `DOCKER_HOST` must point to the Podman socket before running `mvn test`:

```bash
DOCKER_HOST=unix://${XDG_RUNTIME_DIR}/podman/podman.sock mvn test
```

If Docker is available and `DOCKER_HOST` is already set, no action is needed.

When constructing shell commands, resolve any environment variables once (e.g. run `echo $XDG_RUNTIME_DIR` first, note the result) and then embed the literal value in subsequent commands. Do not use `${VAR}` expansions inline — Claude Code's permission system prompts on every command that contains a variable expansion, even if the variable was already approved before.

## Test resources

`src/test/resources/examples` is a symlink to the `argo-workflows` submodule. Never create or modify files inside it. Put custom test fixtures directly under `src/test/resources/` (e.g. `src/test/resources/my-fixture.yaml`).
