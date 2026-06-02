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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.UninitializedNode;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.testutil.WorkflowReleaseGate;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Demonstrates and verifies how the live workflow tree expands as a recursive
 * steps template self-invokes.
 *
 * Topology (countdown-loop.yaml):
 *
 *   main (DAG)
 *   └── loop: count-down(remaining=3) — steps template
 *         ├── wait-and-count   polls HTTP until released, then prints remaining-1
 *         └── recurse          calls count-down again if remaining-1 != 0
 *
 * At parse time the tree is already built one level deep:
 *   loop.get("wait-and-count") → PodRun (pending)
 *   loop.get("recurse")        → UninitializedNode (pending — recursion boundary)
 *
 * When "recurse" executes it expands the UninitializedNode into the next
 * StepsRun, which in turn contains another UninitializedNode for its own
 * "recurse" step, and so on until remaining reaches 0 and the last recurse
 * is skipped.
 *
 * The test captures two summaries:
 *  1. While wait-and-count is blocked on HTTP (recurse is still an unexpanded node)
 *  2. After the workflow finishes (three fully-expanded levels visible in the tree)
 */
class CountdownLoopTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void treeExpandsLevelByLevelDuringRecursion() throws Exception {
        try (var gate = new WorkflowReleaseGate()) {
            Workflow wf = YAML.readValue(getClass().getResource("/countdown-loop.yaml"), Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "release_port".equals(p.getName()))
                    .findFirst().orElseThrow()
                    .setValue(String.valueOf(gate.port()));

            WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
            DagRun main = (DagRun) live.entrypoint();
            StepsRun loop = (StepsRun) main.get("loop");

            // Wait until the first wait-and-count is running (blocked on HTTP)
            long deadline = System.currentTimeMillis() + 30_000;
            while (!loop.get("wait-and-count").running()) {
                if (System.currentTimeMillis() > deadline) fail("wait-and-count did not start within 30s");
                Thread.sleep(100);
            }

            // At this point: wait-and-count is RUNNING, recurse is still an UninitializedNode
            assertThat("recurse should be uninitialized before first wait completes",
                    loop.get("recurse") instanceof UninitializedNode, is(true));
            assertThat("recurse should be pending",
                    loop.get("recurse").pending(), is(true));

            System.out.println("=== While wait-and-count is running (recurse not yet expanded) ===");
            String format = WorkflowSummary.format(live);
            System.out.println(format);
            assertThat(format, is("""
                    Status:  Unknown

                    STEP                    DURATION  MESSAGE
                     ◷ main
                     └─◷ loop
                        ├─◷ wait-and-count  0s
                        └─· recurse
                    """));

            gate.release(); // release permanently — levels 2 and 3 will run immediately

            live.await();
            assertThat(live.succeeded(), is(true));

            // Verify the three-level recursive structure in the completed tree
            UninitializedNode recurse1 = (UninitializedNode) loop.get("recurse");
            assertThat("level-1 recurse should have expanded", recurse1.resolved(), notNullValue());

            StepsRun level2 = (StepsRun) recurse1.resolved();
            assertThat("level-2 succeeded", level2.succeeded(), is(true));

            UninitializedNode recurse2 = (UninitializedNode) level2.get("recurse");
            assertThat("level-2 recurse should have expanded", recurse2.resolved(), notNullValue());

            StepsRun level3 = (StepsRun) recurse2.resolved();
            assertThat("level-3 succeeded", level3.succeeded(), is(true));

            UninitializedNode recurse3 = (UninitializedNode) level3.get("recurse");
            assertThat("level-3 recurse should be skipped (counter reached 0)",
                    recurse3.skipped(), is(true));
            assertThat("level-3 recurse should not have expanded", recurse3.resolved() == null, is(true));

            System.out.println("=== After completion (full three-level recursive tree) ===");
            System.out.println(WorkflowSummary.format(live));
        }
    }
}
