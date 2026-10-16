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
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;
import java.util.Map;

/**
 * Runs the env-var-name substitution scenarios through argo-junit, with the secret and config
 * map served by kwok.
 *
 * @see EnvVarNameSubstitutionBase
 */
class EnvVarNameSubstitutionTest extends EnvVarNameSubstitutionBase {

    static KwokContainer kwok;

    @BeforeAll
    static void setUpKwok() {
        kwok = new KwokContainer();
        kwok.start();
        try (KubernetesClient k8s = kwok.createClient()) {
            k8s.secrets().inNamespace("default").resource(new SecretBuilder()
                            .withNewMetadata().withName(SECRET_NAME).endMetadata()
                            .withStringData(Map.of(SECRET_KEY, SECRET_VALUE))
                            .build())
                    .create();
            k8s.configMaps().inNamespace("default").resource(new ConfigMapBuilder()
                            .withNewMetadata().withName(CONFIG_MAP_NAME).endMetadata()
                            .withData(Map.of(CONFIG_MAP_KEY, CONFIG_MAP_VALUE))
                            .build())
                    .create();
        }
    }

    @AfterAll
    static void tearDownKwok() {
        if (kwok != null) kwok.stop();
    }

    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(workflowYaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            return new WorkflowOutcome(run.succeeded(), WorkflowSummary.format(run));
        }
    }
}
