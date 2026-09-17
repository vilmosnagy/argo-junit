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
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.WorkflowReleaseGate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that {@link WorkflowRun} exposes workflow-level timing, mirroring the per-step
 * {@link PodRun#startedAt()} / {@link PodRun#duration()} pair.
 *
 * <p>The workflow's single step polls a {@link WorkflowReleaseGate} until the test releases it,
 * so the test controls how long the run takes and can inspect timing while it is still running.
 */
class WorkflowRunTimingTest {

    @Test
    void exposesWorkflowLevelStartTimeAndDuration() throws Exception {
        try (var gate = new WorkflowReleaseGate()) {
            Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(
                    getClass().getResource("/workflow-run-timing.yaml"), Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "release_port".equals(p.getName())).findFirst().orElseThrow()
                    .setValue(String.valueOf(gate.port()));

            Instant beforeDispatch = Instant.now();

            try (WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync()) {
                Instant afterDispatch = Instant.now();

                // startedAt() is stamped when executeAsync() dispatches the first step
                assertThat("startedAt must be set", live.startedAt(), notNullValue());
                assertThat("startedAt must not predate dispatch",
                        !live.startedAt().isBefore(beforeDispatch), is(true));
                assertThat("startedAt must not postdate dispatch returning",
                        !live.startedAt().isAfter(afterDispatch), is(true));

                PodRun main = (PodRun) live.entrypoint();
                long deadline = System.currentTimeMillis() + 60_000;
                while (!main.running()) {
                    if (System.currentTimeMillis() > deadline) fail("main did not start within 60s");
                    Thread.sleep(100);
                }

                // While running, duration() tracks wall-clock and keeps growing
                Duration whileRunning = live.duration();
                assertThat("duration must be positive while running",
                        whileRunning.toMillis() > 0, is(true));
                Thread.sleep(500);
                assertThat("duration must grow while the workflow is still running",
                        live.duration(), greaterThanOrEqualTo(whileRunning.plusMillis(400)));

                gate.release();
                live.await(Duration.ofMinutes(10));
                assertThat(live.succeeded(), is(true));

                // Once complete, duration() freezes at the total run time
                Duration afterCompletion = live.duration();
                assertThat("completed duration must cover the running measurement",
                        afterCompletion, greaterThanOrEqualTo(whileRunning));
                Thread.sleep(500);
                assertThat("duration must not change after completion",
                        live.duration(), is(afterCompletion));
            }
        }
    }
}
