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
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that {{tasks.<name>.outputs.result}} is substituted correctly in DAG when conditions.
 * Before the fix this throws IllegalArgumentException: parsing error in '{'.
 */
class DagTaskOutputResultWhenTest {

    @Test
    void taskOutputResultDrivesWhenCondition() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/dag-task-result-when.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            PodRun pathA = (PodRun) dag.get("path-a");
            assertThat("path-a ran",    pathA.succeeded(), is(true));
            assertThat("path-a output", pathA.logs().trim(), containsString("took path A"));

            PodRun pathB = (PodRun) dag.get("path-b");
            assertThat("path-b omitted", pathB.omitted(), is(true));
        }
    }
}
