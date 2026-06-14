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
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: {{workflow.status}} used without surrounding quotes in a DAG when condition
 * (outside the exit handler) must resolve to "Running" rather than being left as a raw token.
 * When unresolved, JEXL parses the braces as a set literal and throws
 * "undefined property 'status'" when it tries to evaluate the expression.
 */
class WorkflowStatusInWhenTest {

    @Test
    void workflowStatusInNonExitHandlerWhenConditionIsResolved() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: workflow-status-in-when-
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: step-a
                            template: noop
                          - name: step-b
                            depends: step-a
                            when: "{{workflow.status}} != Succeeded"
                            template: noop
                    - name: noop
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: "true"
                """).execute(Duration.ofMinutes(5))) {

            assertTrue(run.succeeded(),
                    "Workflow with {{workflow.status}} in a non-exit-handler when condition must succeed");

            DagRun dag = (DagRun) run.entrypoint();
            assertThat("step-a succeeded", dag.get("step-a").succeeded(), is(true));
            // {{workflow.status}} resolves to "Running" during entrypoint execution;
            // "Running" != Succeeded is true, so step-b must run (not be skipped).
            assertThat("step-b ran",       dag.get("step-b").succeeded(), is(true));
        }
    }
}
