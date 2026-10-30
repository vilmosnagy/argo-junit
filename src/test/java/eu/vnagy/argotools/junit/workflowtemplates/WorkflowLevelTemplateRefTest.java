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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs the workflow-level {@code workflowTemplateRef} scenarios through argo-junit, with the
 * WorkflowTemplates served by kwok. Also checks what only argo-junit can observe: the
 * WorkflowTemplate's {@code onExit} handler, and failing fast when the reference cannot be
 * resolved.
 *
 * @see WorkflowLevelTemplateRefBase
 */
class WorkflowLevelTemplateRefTest extends WorkflowLevelTemplateRefBase {

    static ArgoKwok argoKwok;

    @BeforeAll
    static void setUpKwok() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
    }

    @AfterAll
    static void tearDownKwok() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Override
    protected void applyTemplate(String templateYaml) {
        argoKwok.applyYaml(new ByteArrayInputStream(templateYaml.getBytes(StandardCharsets.UTF_8)), _ -> true);
    }

    /**
     * Runs the workflow; a spec the executor rejects up front counts as a failed run, the way
     * Argo marks a workflow with an invalid spec {@code Failed}.
     */
    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        ArgoWorkflowExecutor executor = ArgoWorkflowExecutor.from(workflowYaml).withKwok(argoKwok.container());
        WorkflowRun started;
        try {
            started = executor.executeAsync();
        } catch (IllegalArgumentException e) {
            return new WorkflowOutcome(false, e.getMessage());
        }
        try (WorkflowRun run = started.await(Duration.ofMinutes(10))) {
            return new WorkflowOutcome(run.succeeded(), WorkflowSummary.format(run));
        }
    }

    @Test
    void onExitFallsBackToTemplateOnExit() throws Exception {
        applyTemplate("""
                apiVersion: argoproj.io/v1alpha1
                kind: WorkflowTemplate
                metadata:
                  name: wftref-on-exit
                spec:
                  entrypoint: main
                  onExit: exit-handler
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          true
                    - name: exit-handler
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{workflow.status}}" = "Succeeded"
                """);

        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                        apiVersion: argoproj.io/v1alpha1
                        kind: Workflow
                        metadata:
                          name: wftref-on-exit-test
                        spec:
                          workflowTemplateRef:
                            name: wftref-on-exit
                        """)
                .withKwok(argoKwok.container())
                .execute(Duration.ofMinutes(10))) {
            assertThat(WorkflowSummary.format(run), run.succeeded(), is(true));
            assertThat("the WorkflowTemplate's onExit handler must run", run.hasExitHandler(), is(true));
            assertThat(WorkflowSummary.format(run), run.exitHandler().succeeded(), is(true));
        }
    }

    @Test
    void missingWorkflowTemplateFailsFast() {
        ArgoWorkflowExecutor executor = ArgoWorkflowExecutor.from("""
                        apiVersion: argoproj.io/v1alpha1
                        kind: Workflow
                        metadata:
                          name: wftref-missing-test
                        spec:
                          workflowTemplateRef:
                            name: wftref-does-not-exist
                        """)
                .withKwok(argoKwok.container());

        IllegalStateException e = assertThrows(IllegalStateException.class, executor::executeAsync);
        assertThat(e.getMessage(), containsString("WorkflowTemplate 'wftref-does-not-exist'"));
        assertThat(e.getMessage(), containsString("not found"));
    }

    @Test
    void missingClusterWorkflowTemplateFailsFast() {
        ArgoWorkflowExecutor executor = ArgoWorkflowExecutor.from("""
                        apiVersion: argoproj.io/v1alpha1
                        kind: Workflow
                        metadata:
                          name: wftref-missing-cluster-test
                        spec:
                          workflowTemplateRef:
                            name: wftref-does-not-exist
                            clusterScope: true
                        """)
                .withKwok(argoKwok.container());

        IllegalStateException e = assertThrows(IllegalStateException.class, executor::executeAsync);
        assertThat(e.getMessage(), containsString("ClusterWorkflowTemplate 'wftref-does-not-exist'"));
        assertThat(e.getMessage(), containsString("not found"));
    }

    @Test
    void refWithoutKubernetesClientFailsFast() {
        ArgoWorkflowExecutor executor = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: wftref-no-kwok-test
                spec:
                  workflowTemplateRef:
                    name: wftref-ref-only
                """);

        IllegalStateException e = assertThrows(IllegalStateException.class, executor::executeAsync);
        assertThat(e.getMessage(), containsString("workflowTemplateRef"));
        assertThat(e.getMessage(), containsString("withKwok"));
    }
}
