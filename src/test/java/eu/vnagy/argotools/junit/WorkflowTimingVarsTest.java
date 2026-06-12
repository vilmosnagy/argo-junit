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
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.WorkflowReleaseGate;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that {{workflow.creationTimestamp}} and {{workflow.duration}} are substituted
 * with real values when used as step arguments inside an onExit handler.
 */
class WorkflowTimingVarsTest {

    @Test
    void creationTimestampIsResolvedInNormalStep() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/workflow-creation-timestamp-normal.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat(run.succeeded(), is(true));

            PodRun main = (PodRun) run.entrypoint();
            assertThat(main.logs(), matchesPattern("(?s).*ts=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
        }
    }

    @Test
    void timingVarsAreSubstitutedInExitHandler() throws Exception {
        try (var gate = new WorkflowReleaseGate()) {
            Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(
                    getClass().getResource("/workflow-timing-vars.yaml"), Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "release_port".equals(p.getName())).findFirst().orElseThrow()
                    .setValue(String.valueOf(gate.port()));

            WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
            PodRun main = (PodRun) live.entrypoint();

            long deadline = System.currentTimeMillis() + 60_000;
            while (!main.running()) {
                if (System.currentTimeMillis() > deadline) fail("main did not start within 60s");
                Thread.sleep(100);
            }

            // Let the workflow accumulate at least 2 seconds before releasing
            Thread.sleep(2_000);
            gate.release();

            live.await(Duration.ofMinutes(10));
            assertThat(live.succeeded(), is(true));

            StepsRun onExit = (StepsRun) live.exitHandler();
            PodRun report = (PodRun) onExit.get("report");
            String logs = report.logs();

            // creationTimestamp must be an ISO-8601 datetime, not the raw expression
            assertThat(logs, matchesPattern("(?s).*ts=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));

            // duration must be >= 2s (guaranteed by the gate delay above)
            assertThat(logs, matchesPattern("(?s).*dur=[2-9]\\d*.*"));
        }
    }
}
