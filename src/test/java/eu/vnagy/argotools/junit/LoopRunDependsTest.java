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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class LoopRunDependsTest {

    @Test
    void allSucceededEnablesSucceededDependant() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/loop-depends-all-succeed.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            DagRun dag = (DagRun) run.entrypoint();

            assertThat("process-each succeeded", dag.get("process-each").succeeded(), is(true));
            assertThat("notify-done ran",        dag.get("notify-done").succeeded(),  is(true));
            assertThat("on-failure omitted",     dag.get("on-failure").omitted(),     is(true));
        }
    }

    @Test
    void anySucceededAndAllFailedQualifiers() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/loop-any-succeeded-all-failed.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            DagRun dag = (DagRun) run.entrypoint();

            // task-1: 1 succeeds + 1 fails  →  AnySucceeded=true, AllFailed=false
            assertThat("on-any-succeeded ran",      dag.get("on-any-succeeded").succeeded(), is(true));
            assertThat("not-all-failed omitted",    dag.get("not-all-failed").omitted(),     is(true));

            // task-2: all fail  →  AnySucceeded=false, AllFailed=true
            assertThat("on-all-failed ran",         dag.get("on-all-failed").succeeded(),    is(true));
            assertThat("not-any-succeeded omitted", dag.get("not-any-succeeded").omitted(),  is(true));

            // doc example: task-1.AnySucceeded || task-2.AllFailed
            assertThat("combined ran",              dag.get("combined").succeeded(),          is(true));
        }
    }

    @Test
    void oneFailedEnablesFailedDependant() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/loop-depends-one-fails.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            DagRun dag = (DagRun) run.entrypoint();

            assertThat("process-each not succeeded", dag.get("process-each").succeeded(), is(false));
            assertThat("process-each failed",        dag.get("process-each").failed(),    is(true));
            assertThat("notify-done omitted",        dag.get("notify-done").omitted(),    is(true));
            assertThat("on-failure ran",             dag.get("on-failure").succeeded(),   is(true));
        }
    }
}
