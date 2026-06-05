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
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

class DagTaskWhenTest {

    @Test
    void onlyMatchingTaskRuns() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper()
                .readValue(Path.of(getClass().getResource("/dag-task-when.yaml").toURI()).toFile(),
                        Workflow.class);
        setParam(wf, "mode", "a");

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute(Duration.ofMinutes(10))) {
            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            PodRun runA = (PodRun) dag.get("run-a");
            assertThat("run-a ran",    runA.succeeded(), is(true));
            assertThat("run-a output", runA.logs().trim(), containsString("ran-a"));

            PodRun runB = (PodRun) dag.get("run-b");
            assertThat("run-b omitted", runB.omitted(), is(true));
        }
    }

    private static void setParam(Workflow wf, String name, String value) {
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .ifPresent(p -> p.setValue(value));
    }
}
