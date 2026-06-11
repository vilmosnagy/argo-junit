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
import eu.vnagy.argotools.junit.executor.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class LoopOutputParamFanoutTest {

    @Test
    void aggregatedLoopOutputParametersCanBeUsedAsWithParamForNextTask() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/loop-output-param-fanout.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            List<WorkflowNode> processIterations = dag.get("process").children();

            assertThat("two process iterations", processIterations.size(), is(2));
            assertThat("all succeeded", processIterations.stream().allMatch(WorkflowNode::succeeded), is(true));

            Set<String> outputs = processIterations.stream()
                    .map(n -> ((PodRun) n).logs().trim())
                    .collect(Collectors.toSet());
            assertThat(outputs, containsInAnyOrder("alpha:v1-alpha", "beta:v1-beta"));
        }
    }
}
