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
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.RetryOutcomeGate;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

/**
 * Verifies that {@code spec.retryStrategy} is honoured as a workflow-wide retry default
 * when {@code spec.templateDefaults.retryStrategy} is absent.
 *
 * <p>Before the fix, only {@code spec.templateDefaults.retryStrategy} was consulted;
 * {@code spec.retryStrategy} was silently ignored, so pods received zero-retry behaviour.
 */
class SpecRetryStrategyTest {

    @Test
    void specRetryStrategy_appliedWhenTemplateDefaultsAbsent() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            Workflow wf = ArgoWorkflowExecutor.yamlMapper()
                    .readValue(getClass().getResource("/spec-retry-strategy.yaml"), Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "port".equals(p.getName()))
                    .findFirst().orElseThrow()
                    .setValue(String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute(Duration.ofMinutes(10))) {
                assertThat("workflow succeeded via spec.retryStrategy", run.succeeded(), is(true));
                assertThat("3 attempts (spec.retryStrategy limit covers 2 retries)",
                        ((PodRun) run.entrypoint()).attempts(), is(3));
            }
        }
    }
}
