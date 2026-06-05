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
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

/**
 * Verifies that the diamond-coinflip DAG runs to completion.
 *
 * Topology (dag-coinflip.yaml):
 *
 *   A ──────────────► B ──► D
 *   └───────────────► C ──► D
 *
 * Each node runs the {@code coinflip} steps template, which recursively re-runs
 * itself on tails until it gets heads. B and C run in parallel after A completes;
 * D waits for both B and C.
 *
 * The test only asserts overall success — the number of recursive coin flips per
 * node is non-deterministic.
 */
class DagCoinflipTest {

    @Test
    void diamondWithRecursiveCoinflip() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-coinflip.yaml").toURI()))
                .execute(Duration.ofMinutes(10));

        assertThat(run.succeeded(), is(true));

        DagRun dag = (DagRun) run.entrypoint();
        for (String task : new String[]{"A", "B", "C", "D"}) {
            assertThat("task " + task, dag.get(task).succeeded(), is(true));
        }

        System.out.println(WorkflowSummary.format(run));
    }
}
