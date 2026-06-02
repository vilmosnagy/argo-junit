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

/**
 * Verifies continue-on-fail DAG behaviour against dag-continue-on-fail.yaml.
 *
 * Topology and expected terminal states:
 *
 *   A              → SUCCEEDED   (no deps)
 *   B   dep: A     → FAILED      (intentional-fail)
 *   C   dep: A     → SUCCEEDED
 *   E   dep: A     → FAILED      (intentional-fail)
 *   F   dep: A     → SUCCEEDED
 *
 *   D   dep: B.Failed && C       → SUCCEEDED
 *       B.Failed=true, C (bare)=C.Succeeded=true  →  true
 *
 *   G   dep: E && F              → OMITTED
 *       E (bare) = E.Succeeded || E.Skipped = false  →  false
 *
 * The overall DAG fails (B and E fail), but D still runs because its depends
 * expression explicitly handles a failed upstream.
 */
class DagContinueOnFailTest {

    @Test
    void continueOnFailRunsDependentOnFailedTask() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-continue-on-fail.yaml").toURI()))
                .execute();

        DagRun dag = (DagRun) run.entrypoint();

        assertThat("A", dag.get("A").succeeded(), is(true));
        assertThat("B", dag.get("B").failed(),    is(true));
        assertThat("C", dag.get("C").succeeded(), is(true));
        assertThat("D", dag.get("D").succeeded(), is(true));  // ran because B.Failed && C was true
        assertThat("E", dag.get("E").failed(),    is(true));
        assertThat("F", dag.get("F").succeeded(), is(true));
        assertThat("G", dag.get("G").omitted(),   is(true));  // E (bare) false because E failed

        assertThat("dag failed overall", run.failed(), is(true));

        System.out.println(WorkflowSummary.format(run));
    }
}
