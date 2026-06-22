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
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: when an output artifact's {@code path} contains a
 * {@code {{workflow.parameters.*}}} expression, the expression must be substituted
 * before Docker's {@code copyArchiveFromContainerCmd} is called.
 * <p>
 * Without the fix, the literal string
 * {@code /out/{{workflow.parameters.file-type}}.txt} is passed to Docker, which finds
 * no such path in the container. The exception is swallowed as a WARN and
 * {@code collectedArtifacts()} is left empty — even though the workflow itself reports
 * success (the script exited 0).
 */
class OutputArtifactPathSubstitutionTest {

    @Test
    void outputArtifactWithParameterizedPathIsCollected() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: output-artifact-path-subst-
                spec:
                  entrypoint: generate
                  arguments:
                    parameters:
                      - name: file-type
                        value: result
                  templates:
                    - name: generate
                      outputs:
                        artifacts:
                          - name: output-file
                            path: /out/{{workflow.parameters.file-type}}.txt
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: |
                          mkdir -p /out
                          echo "artifact content" > /out/result.txt
                """).execute(Duration.ofMinutes(5))) {

            assertTrue(run.succeeded(),
                    "Workflow must succeed; got: " + run.entrypoint().message());

            PodRun pod = (PodRun) run.entrypoint();
            assertThat("output artifact with parameterized path must be collected",
                    pod.collectedArtifacts().containsKey("output-file"), is(true));
        }
    }
}
