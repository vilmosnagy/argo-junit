package eu.vnagy.argotools.junit;

/*-
 * #%L
 * Argo JUnit
 * %%
 * Copyright (C) 2026 Vilmos Szabó-Nagy
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: a {@code git:} input artifact must be cloned on the host and injected
 * into the container at the declared path before the script runs.
 * <p>
 * Without a {@code GitArtifactDriver} registered in the ServiceLoader chain,
 * {@code ctx.findDriver()} returns {@code Optional.empty()} and the artifact is silently
 * dropped — the container starts with the target path missing and any script that
 * references it fails with a file-not-found error.
 */
class GitArtifactDriverTest {

    @Test
    void gitInputArtifactIsInjectedIntoContainer() throws Exception {
        // Uses the argo-junit repo itself at a known released tag — small, public, stable.
        // The container only needs to verify the cloned file is present; it does NOT need
        // git installed because the clone happens on the host inside the driver.
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: git-artifact-
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      inputs:
                        artifacts:
                          - name: repo-source
                            path: /repo
                            git:
                              repo: https://github.com/vilmosnagy/argo-junit.git
                              revision: v0.0.7
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: |
                          test -f /repo/pom.xml
                """).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Workflow with git input artifact must succeed — pom.xml must be present at /repo; got: "
                            + run.entrypoint().message());
        }
    }
}
