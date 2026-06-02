package eu.vnagy.argotools.junit.workflowtemplates;

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
import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Reproduces the two-part retry bug when a WorkflowTemplate uses a YAML merge key
 * ({@code <<: *anchor}) to supply the {@code retryStrategy.limit}.
 *
 * <p>Bug 1: {@code scheduleWTKey} converts the raw kwok Map with
 * {@code JSON.convertValue}, which does not resolve YAML merge keys. The
 * {@code retryStrategy} object is non-null (the key exists in the YAML) but
 * {@code limit} is {@code null} because the {@code "<<"} entry is dropped as an
 * unknown field.
 *
 * <p>Bug 2: {@code ResolvedRetry.from} treats a non-null {@code templateRs} as
 * authoritative and never consults {@code defaultRs}. With {@code limit=null} it
 * falls back to {@code -1}, which {@code shouldRetry} interprets as "unlimited".
 *
 * <p>Without both fixes this test hangs; the {@code @Timeout} turns the hang into
 * a clean failure.
 */
class MergeKeyRetryTest {

    static ArgoKwok argoKwok;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        argoKwok.applyYaml("/wftemplate/merge-key-retry/wt-merge-key-retry.yaml");
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void mergeKeyRetryLimitFallsBackToTemplateDefaults() throws Exception {
        Path workflowPath = Path.of(
                getClass().getResource("/wftemplate/merge-key-retry/workflow.yaml").toURI());

        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflowPath)
                .withKwok(argoKwok.container())
                .execute()) {

            assertThat("workflow failed", run.failed(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            PodRun runner = (PodRun) dag.get("run");
            // templateDefaults limit=2 → 3 total attempts (1 original + 2 retries)
            assertThat("attempts capped by templateDefaults",
                    runner.attempts(), is(3));
        }
    }
}
