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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.time.Duration;

/**
 * Runs the input artifact default scenarios through argo-junit.
 *
 * @see InputArtifactDefaultBase
 */
class InputArtifactDefaultTest extends InputArtifactDefaultBase {

    static KwokContainer kwok;

    @BeforeAll
    static void setUpKwok() {
        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret(SECRET_NAME, "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDownKwok() {
        if (kwok != null) kwok.stop();
    }

    @Override
    protected String s3Endpoint() {
        return minio.endpoint();
    }

    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(workflowYaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            return new WorkflowOutcome(run.succeeded(), WorkflowSummary.format(run));
        }
    }
}
