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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

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
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/workflow-timing-vars.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat(run.succeeded(), is(true));

            StepsRun onExit = (StepsRun) run.exitHandler();
            PodRun report = (PodRun) onExit.get("report");
            String logs = report.logs();

            // creationTimestamp must be an ISO-8601 datetime, not the raw expression
            assertThat(logs, matchesPattern("(?s).*ts=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));

            // duration must be a positive integer (real elapsed seconds), not the stub "0"
            assertThat(logs, matchesPattern("(?s).*dur=[1-9]\\d*.*"));
        }
    }
}
