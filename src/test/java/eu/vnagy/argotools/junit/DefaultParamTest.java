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
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

class DefaultParamTest {

    @Test
    void dagMissingParameterFallsBackToTemplateDefault() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/dag-default-param.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            PodRun runner = (PodRun) dag.get("run");
            assertThat("runner succeeded", runner.succeeded(), is(true));
            assertThat("output uses default separator",
                    runner.logs().trim(), containsString("a, b, c"));
        }
    }

    @Test
    void stepsMissingParameterFallsBackToTemplateDefault() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/steps-default-param.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            StepsRun steps = (StepsRun) run.entrypoint();
            PodRun runner = (PodRun) steps.get("run");
            assertThat("runner succeeded", runner.succeeded(), is(true));
            assertThat("output uses default separator",
                    runner.logs().trim(), containsString("a, b, c"));
        }
    }

    // Argo appears to treat `value:` and `default:` identically for template input parameters —
    // both act as the fallback when the caller omits the argument. We haven't verified this
    // in the Argo source, but it is consistent with what we observe in production workflows.

    @Test
    void dagMissingParameterFallsBackToTemplateDefaultKeyword() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/dag-default-keyword-param.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            PodRun runner = (PodRun) dag.get("run");
            assertThat("runner succeeded", runner.succeeded(), is(true));
            assertThat("output uses default: keyword separator",
                    runner.logs().trim(), containsString("a, b, c"));
        }
    }

    @Test
    void stepsMissingParameterFallsBackToTemplateDefaultKeyword() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/steps-default-keyword-param.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            StepsRun steps = (StepsRun) run.entrypoint();
            PodRun runner = (PodRun) steps.get("run");
            assertThat("runner succeeded", runner.succeeded(), is(true));
            assertThat("output uses default: keyword separator",
                    runner.logs().trim(), containsString("a, b, c"));
        }
    }
}
