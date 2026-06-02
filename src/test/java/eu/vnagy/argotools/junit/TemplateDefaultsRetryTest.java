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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.RetryOutcomeGate;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that {@code spec.templateDefaults.retryStrategy} is used as a fallback when a
 * template declares no retryStrategy of its own, and that a template's own retryStrategy
 * takes precedence over the defaults.
 */
class TemplateDefaultsRetryTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The fixture has {@code templateDefaults.retryStrategy.limit: "5"} and no per-template
     * retryStrategy. The gate queues two failures then a success, so the executor retries
     * twice and the workflow succeeds on the third attempt.
     */
    @Test
    void templateDefaultsAppliedWhenTemplateHasNoRetry() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            try (WorkflowRun run = execute(gate)) {
                assertThat("workflow succeeded via default retry", run.succeeded(), is(true));
                assertThat("3 attempts (default limit covers 2 retries)",
                        ((PodRun) run.entrypoint()).attempts(), is(3));
            }
        }
    }

    /**
     * When a template declares its own {@code retryStrategy.limit: "0"}, that overrides
     * the workflow-level {@code templateDefaults.retryStrategy.limit: "5"} — no retries occur.
     */
    @Test
    void templateOwnRetryStrategyOverridesDefaults() throws Exception {
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: template-defaults-override-
                spec:
                  entrypoint: poll-gate
                  templateDefaults:
                    retryStrategy:
                      limit: "5"
                  arguments:
                    parameters:
                    - name: port
                      value: "0"
                  templates:
                  - name: poll-gate
                    retryStrategy:
                      limit: "0"
                    container:
                      image: busybox
                      command: [sh, -c]
                      args:
                      - |
                        while true; do
                          RESULT=$(wget -qO- http://host.docker.internal:{{workflow.parameters.port}}/outcome 2>/dev/null)
                          case "$RESULT" in
                            succeed) exit 0 ;;
                            fail) exit 1 ;;
                          esac
                          sleep 0.1
                        done
                """;

        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();

            Workflow wf = YAML.readValue(yaml, Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "port".equals(p.getName()))
                    .findFirst().orElseThrow()
                    .setValue(String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("workflow failed without retrying", run.failed(), is(true));
                assertThat("exactly 1 attempt (template limit:0 overrides defaults)",
                        ((PodRun) run.entrypoint()).attempts(), is(1));
            }
        }
    }

    private WorkflowRun execute(RetryOutcomeGate gate) throws Exception {
        Workflow wf = YAML.readValue(getClass().getResource("/template-defaults-retry.yaml"), Workflow.class);
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "port".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(String.valueOf(gate.port()));
        return ArgoWorkflowExecutor.from(wf).execute();
    }
}
