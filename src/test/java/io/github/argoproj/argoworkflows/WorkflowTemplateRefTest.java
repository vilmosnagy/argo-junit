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
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowNode;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

/**
 * Runs upstream Argo workflow-template example workflows through the argo-junit executor,
 * verifying end-to-end {@code templateRef} handling.
 *
 * <p>A single {@link ArgoKwok} cluster is started once for the class; the Argo CRDs and
 * all WorkflowTemplate definitions from {@code examples/workflow-template/templates.yaml}
 * are applied once in {@code @BeforeAll}. Each {@code @Test} method creates its own
 * {@link ArgoWorkflowExecutor} instance (sharing the same kwok via
 * {@link ArgoWorkflowExecutor#withKwok}), so container startup cost is paid only once.
 *
 * <p>The executor resolves {@code templateRef} fields by fetching the named WorkflowTemplate
 * from kwok and merging its templates into the execution graph before running any steps.
 */
public class WorkflowTemplateRefTest {

    static ArgoKwok argoKwok;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        argoKwok.applyYaml("/examples/workflow-template/templates.yaml");
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void helloWorldViaTemplateRef() throws Exception {
        try (WorkflowRun run = executorFor("hello-world.yaml").execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
        }
    }

    @Test
    void stepsViaTemplateRef() throws Exception {
        try (WorkflowRun run = executorFor("steps.yaml").execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
        }
    }

    @Test
    void dagDiamondViaTemplateRef() throws Exception {
        try (WorkflowRun run = executorFor("dag.yaml").execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
        }
    }

    @Test
    void retryWithStepsViaTemplateRef() throws Exception {
        try (WorkflowRun run = executorFor("retry-with-steps.yaml").execute(Duration.ofMinutes(10))) {
            if (!run.succeeded()) {
                // Each step retries up to 10 times (limit: 10 in the templateRef), so any step
                // that failed must have exhausted all 11 attempts before giving up.
                StepsRun stepsRun = (StepsRun) run.entrypoint();
                for (WorkflowNode step : stepsRun.steps()) {
                    if (step.failed()) {
                        assertThat(step.name() + " must have exhausted all retries",
                                step.attempts(), is(11));
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------

    private static ArgoWorkflowExecutor executorFor(String filename) throws Exception {
        URI resource = WorkflowTemplateRefTest.class
                .getResource("/examples/workflow-template/" + filename).toURI();
        return ArgoWorkflowExecutor.from(Path.of(resource)).withKwok(argoKwok.container());
    }
}
