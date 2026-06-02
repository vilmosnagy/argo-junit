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

import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Verifies that Argo WorkflowTemplate resources can be applied to a kwok cluster via
 * {@link ArgoKwok}.
 *
 * <p><strong>Scope:</strong> this test exercises only the kwok-side infrastructure —
 * starting a cluster, installing the Argo CRDs, and confirming that WorkflowTemplate
 * objects survive a Kubernetes API round-trip. It does <em>not</em> test that the
 * argo-junit executor can actually interpret or run workflows that reference those
 * templates. For end-to-end {@code templateRef} execution, see
 * {@link io.github.argoproj.argoworkflows.WorkflowTemplateRefTest}.
 */
class WorkflowTemplateKwokTest {

    static ArgoKwok argoKwok;
    static KubernetesClient k8s;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        k8s = argoKwok.createClient();
        argoKwok.applyYaml("/examples/workflow-template/templates.yaml");
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void allWorkflowTemplatesApplied() {
        List<GenericKubernetesResource> templates = k8s
                .genericKubernetesResources(eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor.WORKFLOW_TEMPLATE_CTX)
                .inNamespace("default")
                .list()
                .getItems();

        assertThat("all five templates.yaml entries should be present", templates, hasSize(5));
    }

    @Test
    void workflowTemplateHasExpectedStructure() {
        GenericKubernetesResource printMessage = k8s
                .genericKubernetesResources(eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor.WORKFLOW_TEMPLATE_CTX)
                .inNamespace("default")
                .withName("workflow-template-print-message")
                .get();

        assertThat("workflow-template-print-message should exist", printMessage, notNullValue());
        assertThat("spec should be present", printMessage.get("spec"), notNullValue());
    }
}
