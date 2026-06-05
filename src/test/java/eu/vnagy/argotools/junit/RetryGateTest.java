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
import java.time.Duration;

/**
 * Deterministic retry tests driven by {@link RetryOutcomeGate}.
 *
 * Each test pre-loads the gate queue with scripted outcomes so that
 * retry behaviour is fully controlled and not subject to probability.
 */
class RetryGateTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** OnFailure (default) — retries on non-zero exit, succeeds when gate says so. */
    @Test
    void onFailure_succeedsAfterGatedRetries() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            try (WorkflowRun run = execute("retry-gate.yaml", gate)) {
                assertThat("workflow succeeded", run.succeeded(), is(true));
                assertThat("exactly 3 attempts", ((PodRun) run.entrypoint()).attempts(), is(3));
            }
        }
    }

    /** OnFailure — retry limit stops execution even when gate still has failures queued. */
    @Test
    void onFailure_limitEnforced() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            for (int i = 0; i < 6; i++) gate.willFail(); // more than limit+1

            try (WorkflowRun run = execute("retry-gate.yaml", gate)) {
                PodRun pod = (PodRun) run.entrypoint();
                assertThat("workflow failed after exhausting retries", run.failed(), is(true));
                assertThat("exactly 6 attempts (1 initial + limit 5)", pod.attempts(), is(6));
            }
        }
    }

    /** OnFailure — single run with no retries needed. */
    @Test
    void onFailure_succeedsFirstAttempt() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willSucceed();

            try (WorkflowRun run = execute("retry-gate.yaml", gate)) {
                assertThat("workflow succeeded without retries", run.succeeded(), is(true));
                assertThat("exactly 1 attempt", ((PodRun) run.entrypoint()).attempts(), is(1));
            }
        }
    }

    /** Always policy — retries on non-zero exit just like OnFailure for gate-controlled failures. */
    @Test
    void always_retriesOnFailure() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            try (WorkflowRun run = execute("retry-gate-always.yaml", gate)) {
                assertThat("workflow succeeded", run.succeeded(), is(true));
                assertThat("exactly 3 attempts", ((PodRun) run.entrypoint()).attempts(), is(3));
            }
        }
    }

    /** Always policy — limit enforced the same way as OnFailure. */
    @Test
    void always_limitEnforced() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            for (int i = 0; i < 6; i++) gate.willFail();

            try (WorkflowRun run = execute("retry-gate-always.yaml", gate)) {
                PodRun pod = (PodRun) run.entrypoint();
                assertThat("workflow failed after exhausting retries", run.failed(), is(true));
                assertThat("exactly 6 attempts (1 initial + limit 5)", pod.attempts(), is(6));
            }
        }
    }

    private WorkflowRun execute(String resource, RetryOutcomeGate gate) throws Exception {
        Workflow wf = YAML.readValue(getClass().getResource("/" + resource), Workflow.class);
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "port".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(String.valueOf(gate.port()));
        return ArgoWorkflowExecutor.from(wf).execute(Duration.ofMinutes(10));
    }
}
