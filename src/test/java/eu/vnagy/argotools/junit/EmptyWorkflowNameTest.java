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
 * Regression test: when {@code metadata.name} is an empty string the sanitized workflow
 * prefix is also empty, causing {@code containerName()} to return a name that starts with
 * {@code _} — rejected by the container runtime with "names must match [a-zA-Z0-9][a-zA-Z0-9_.-]*".
 */
class EmptyWorkflowNameTest {

    @Test
    void workflowWithEmptyMetadataNameRunsSuccessfully() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: ""
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: "echo hello"
                """).execute(Duration.ofMinutes(5))) {
            assertTrue(run.succeeded(),
                    "Workflow with empty metadata.name must succeed; got: " + run.entrypoint().message());
        }
    }

    /**
     * When the workflow name starts with {@code -} it survives the sanitizer unchanged
     * ({@code -} is in the allowed set {@code [a-zA-Z0-9_.-]}), so the resulting container
     * name also starts with {@code -}, which the runtime rejects for the same reason.
     */
    @Test
    void workflowNameWithLeadingHyphenRunsSuccessfully() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: "-workflow"
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: "echo hello"
                """).execute(Duration.ofMinutes(5))) {
            assertTrue(run.succeeded(),
                    "Workflow with leading-hyphen name must succeed; got: " + run.entrypoint().message());
        }
    }

    /**
     * The pod name is exactly 53 characters, making {@code tail.length() = 60 = MAX_CONTAINER_NAME}.
     * With a non-empty workflow name the total exceeds the limit, so the prefix is truncated to
     * {@code wfBudget = 60 - 60 = 0} characters — the ternary returns {@code ""} and the result
     * is {@code "" + tail}, which again starts with {@code _}.
     */
    @Test
    void workflowNameTrimmedToZeroByLongTaskNameRunsSuccessfully() throws Exception {
        // Task name is 53 chars: tail = 1 + 53 + 1 + 5 = 60 = MAX_CONTAINER_NAME
        // wfBudget = 60 - 60 = 0, so the workflow prefix is trimmed away entirely.
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: w
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: set-global-vars-skip-exit-handler-email-sending-false
                            template: noop
                    - name: noop
                      script:
                        image: alpine:3.23
                        command: [sh]
                        source: "true"
                """).execute(Duration.ofMinutes(5))) {
            assertTrue(run.succeeded(),
                    "Workflow with wfBudget=0 must succeed; got: " + run.entrypoint().message());
        }
    }
}
