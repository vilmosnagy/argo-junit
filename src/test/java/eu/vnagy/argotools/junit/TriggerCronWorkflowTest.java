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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * kwok runs no Argo cron controller, so a {@code CronWorkflow} applied to the cluster never
 * fires on its own. {@link ArgoKwok#triggerCronWorkflow(String, String)} closes that gap: it
 * reads the already-applied {@code CronWorkflow}, lifts its {@code spec.workflowSpec} and
 * submits it as a one-off {@code Workflow} with a generated name.
 */
class TriggerCronWorkflowTest {

    static final ResourceDefinitionContext WORKFLOW_CTX = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io").withVersion("v1alpha1").withKind("Workflow")
            .withNamespaced(true).build();

    static ArgoKwok argoKwok;
    static KubernetesClient k8s;

    @BeforeAll
    static void setUp() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        k8s = argoKwok.createClient();
        argoKwok.applyYaml("/cron-workflow-trigger.yaml");
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void triggeringACronWorkflowSubmitsItsWorkflowSpecAsAOneOffWorkflow() throws Exception {
        String submittedName = argoKwok.triggerCronWorkflow("default", "cron-trigger-test");

        assertThat(submittedName, is(not(blankOrNullString())));
        assertThat(submittedName, startsWith("cron-trigger-test-"));

        // The triggered Workflow really exists in the cluster...
        GenericKubernetesResource submitted = k8s.genericKubernetesResources(WORKFLOW_CTX)
                .inNamespace("default")
                .withName(submittedName)
                .get();
        assertThat(submitted, is(notNullValue()));
        assertThat(submitted.getKind(), is("Workflow"));

        // ...and it carries a runnable copy of the CronWorkflow's workflowSpec.
        String yaml = k8s.getKubernetesSerialization().asYaml(submitted);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(yaml)
                .withKwok(argoKwok.container())
                .execute(Duration.ofMinutes(5))) {
            assertThat(run.succeeded(), is(true));
        }
    }

    @Test
    void eachTriggerGetsItsOwnGeneratedName() {
        String first = argoKwok.triggerCronWorkflow("default", "cron-trigger-test");
        String second = argoKwok.triggerCronWorkflow("default", "cron-trigger-test");

        assertThat(first, is(not(second)));
        assertThat(k8s.genericKubernetesResources(WORKFLOW_CTX)
                .inNamespace("default").withName(first).get(), is(notNullValue()));
        assertThat(k8s.genericKubernetesResources(WORKFLOW_CTX)
                .inNamespace("default").withName(second).get(), is(notNullValue()));
    }
}
