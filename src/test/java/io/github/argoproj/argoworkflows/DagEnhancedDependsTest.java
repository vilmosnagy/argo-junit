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
 * Verifies enhanced depends logic against dag-enhanced-depends.yaml.
 *
 * Topology and expected terminal states:
 *
 *   A              → SUCCEEDED   (pass, no deps)
 *   B   dep: A     → SUCCEEDED   (pass)
 *   C   dep: A     → FAILED      (fail, exit 1)
 *
 *   should-execute-1   dep: A && (C.Succeeded || C.Failed)  → SUCCEEDED
 *                      A=true, C.Succeeded=false, C.Failed=true  → true
 *
 *   should-execute-2   dep: B || C                          → SUCCEEDED
 *                      B=true → true
 *
 *   should-not-execute dep: B && C                          → OMITTED
 *                      C=false → false
 *
 *   should-execute-3   dep: should-execute-2.Succeeded || should-not-execute → SUCCEEDED
 *                      should-execute-2=true → true
 *
 * The overall DAG is not asserted as succeeded because task C intentionally fails.
 */
class DagEnhancedDependsTest {

    @Test
    void enhancedDependsSkipsAndRunsCorrectly() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-enhanced-depends.yaml").toURI()))
                .execute();

        DagRun dag = (DagRun) run.entrypoint();

        assertThat("A",                dag.get("A").succeeded(),               is(true));
        assertThat("B",                dag.get("B").succeeded(),               is(true));
        assertThat("C",                dag.get("C").failed(),                  is(true));
        assertThat("should-execute-1", dag.get("should-execute-1").succeeded(), is(true));
        assertThat("should-execute-2", dag.get("should-execute-2").succeeded(), is(true));
        assertThat("should-not-execute", dag.get("should-not-execute").omitted(), is(true));
        assertThat("should-execute-3", dag.get("should-execute-3").succeeded(), is(true));

        System.out.println(WorkflowSummary.format(run));
    }
}
