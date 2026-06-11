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
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Verifies that {{workflow.name}} is substituted with a generated name derived from
 * the workflow's generateName prefix plus 5 random lowercase alphanumeric characters.
 */
class WorkflowNameTest {

    @Test
    void explicitNameIsUsedAsIs() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/workflow-name-explicit.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat(run.succeeded(), is(true));

            PodRun main = (PodRun) run.entrypoint();
            assertThat(main.logs(), containsString("name=workflow-name-test-4tjnf"));
        }
    }

    @Test
    void workflowNameIsResolvedInNormalStep() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/workflow-name.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat(run.succeeded(), is(true));

            PodRun main = (PodRun) run.entrypoint();
            assertThat(main.logs(), matchesPattern("(?s).*name=workflow-name-test-[a-z0-9]{5}(?![a-z0-9]).*"));
        }
    }

    @Test
    void workflowNameIsGeneratedFromGenerateName() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/workflow-name.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat(run.succeeded(), is(true));

            PodRun main = (PodRun) run.entrypoint();
            assertThat(main.logs(), matchesPattern("(?s).*name=workflow-name-test-[a-z0-9]{5}(?![a-z0-9]).*"));

            PodRun onExit = (PodRun) run.exitHandler();
            assertThat(onExit.logs(), matchesPattern("(?s).*name=workflow-name-test-[a-z0-9]{5}(?![a-z0-9]).*"));
        }
    }
}
