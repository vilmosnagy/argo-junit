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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Regression test for: volume mount {@code name:} containing a template expression is not
 * substituted before the volume-map lookup, causing the mount to be silently skipped.
 *
 * <p>Both the {@code script} and {@code container} pod-run paths are covered because the bug
 * lives in the shared {@code runAttempt} volume-binding loop and the {@code materializeVolumes}
 * pre-loop, both of which use the raw (unresolved) mount name.
 */
class VolumeMountNameExpressionTest {

    static KwokContainer kwok;

    @BeforeAll
    static void setup() {
        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient().configMaps().inNamespace("default").resource(
                new ConfigMapBuilder()
                        .withNewMetadata().withName("vol-name-expr-config").endMetadata()
                        .withData(Map.of("value.txt", "resolved-volume-name-works"))
                        .build()).create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
    }

    @Test
    void scriptTemplateMountsVolumeWithParameterizedName() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(
                getClass().getResource("/vol-name-expr-script.yaml"), Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            PodRun worker = (PodRun) ((DagRun) run.entrypoint()).get("run");
            assertThat(worker.logs(), containsString("resolved-volume-name-works"));
        }
    }

    @Test
    void containerTemplateMountsVolumeWithParameterizedName() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(
                getClass().getResource("/vol-name-expr-container.yaml"), Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            PodRun worker = (PodRun) ((DagRun) run.entrypoint()).get("run");
            assertThat(worker.logs(), containsString("resolved-volume-name-works"));
        }
    }
}
