package io.github.argoproj.argoworkflows;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class ExitHandlerTest {

    @Test
    void exitHandlerRunsAfterWorkflowFails() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/exit-handlers.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            // The entrypoint does `exit 1` deliberately — workflow fails, not errors
            assertThat(run.failed(), is(true));

            // The exit handler must run regardless of the entrypoint outcome
            StepsRun exitHandler = (StepsRun) run.exitHandler();

            // notify (send-email) always runs; its log should contain the workflow status
            PodRun notify = (PodRun) exitHandler.get("notify");
            assertThat(notify.succeeded(), is(true));
            assertThat(notify.logs(), containsString("Failed"));

            // celebrate is guarded by `when: "{{workflow.status}} == Succeeded"` — skipped on failure
            PodRun celebrate = (PodRun) exitHandler.get("celebrate");
            assertThat(celebrate.skipped(), is(true));

            // cry is guarded by `when: "{{workflow.status}} != Succeeded"` — runs on failure
            PodRun cry = (PodRun) exitHandler.get("cry");
            assertThat(cry.succeeded(), is(true));
            assertThat(cry.logs(), containsString("boohoo!"));
        }
    }
}
