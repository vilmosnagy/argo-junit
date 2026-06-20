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
 * Regression test: {@code {{workflow.parameters.*}}} placeholders inside a {@code git:}
 * artifact's {@code revision} and {@code repo} fields must be substituted before the
 * artifact is handed to {@code GitArtifactDriver}.
 * <p>
 * Without the fix, {@code ExecutionContext.substituteArtifact} short-circuits on
 * {@code art.getS3() == null} and returns the artifact unmodified — the literal string
 * {@code {{workflow.parameters.repo-tag}}} is passed as the {@code --branch} argument,
 * causing {@code git clone} to exit 128 with
 * "fatal: Remote branch {{...}} not found in upstream origin".
 */
class GitArtifactParameterSubstitutionTest {

    @Test
    void workflowParametersInGitArtifactRevisionAreSubstituted() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: git-artifact-param-subst-
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: repo-url
                        value: https://github.com/vilmosnagy/argo-junit.git
                      - name: repo-tag
                        value: v0.0.7
                  templates:
                    - name: main
                      inputs:
                        artifacts:
                          - name: repo-source
                            path: /repo
                            git:
                              repo: '{{workflow.parameters.repo-url}}'
                              revision: '{{workflow.parameters.repo-tag}}'
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: |
                          test -f /repo/pom.xml
                """).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Workflow with {{workflow.parameters.*}} in git artifact fields must succeed; "
                            + "got: " + run.entrypoint().message());
        }
    }
}
