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
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a templateRef only present in an unreachable template of an installed
 * WorkflowTemplate does not prevent execution when the referenced WT is absent.
 *
 * <p>Before the fix, {@code resolveTemplateRefs} eagerly scanned every template in every
 * installed WorkflowTemplate, so a {@code templateRef} buried in an unreachable DAG caused
 * an {@code IllegalStateException} even though that DAG was never invoked.
 */
class UnreachableTemplateRefTest {

    static ArgoKwok argoKwok;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        // "utils" WT has "used-template" (reachable) and "unused-template" (references
        // "missing-wt" which is intentionally not installed).
        argoKwok.applyYaml("/wftemplate/unreachable-ref/utils-wt.yaml");
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void unreachableTemplateRefIsIgnored() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: test-wf
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: call-used
                            templateRef:
                              name: utils
                              template: used-template
                """;

        Workflow workflow = ArgoWorkflowExecutor.yamlMapper().readValue(workflowYaml, Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflow)
                .withKwok(argoKwok.container())
                .execute()) {
            assertTrue(run.succeeded(),
                    "workflow should succeed — 'missing-wt' is unreachable from entrypoint 'main'");
        }
    }
}
