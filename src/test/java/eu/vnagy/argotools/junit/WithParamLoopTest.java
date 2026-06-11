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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

class WithParamLoopTest {

    @Test
    void withParamExpandsIntoMultipleIterations() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/withparam-loop.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            List<WorkflowNode> iterations = dag.get("process-each").children();

            assertThat("three iterations", iterations.size(), is(3));
            assertThat("all succeeded", iterations.stream().allMatch(WorkflowNode::succeeded), is(true));

            Set<String> outputs = iterations.stream()
                    .map(n -> ((PodRun) n).logs().trim())
                    .collect(Collectors.toSet());
            assertThat(outputs, containsInAnyOrder("first=1", "second=2", "third=3"));
        }
    }

    @Test
    void withItemsExpandsIntoMultipleIterations() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/withitems-loop.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            List<WorkflowNode> iterations = dag.get("echo-each").children();

            assertThat("three iterations", iterations.size(), is(3));
            assertThat("all succeeded", iterations.stream().allMatch(WorkflowNode::succeeded), is(true));

            Set<String> outputs = iterations.stream()
                    .map(n -> ((PodRun) n).logs().trim())
                    .collect(Collectors.toSet());
            assertThat(outputs, containsInAnyOrder("alpha", "beta", "gamma"));
        }
    }
}
