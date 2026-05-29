package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.RetryOutcomeGate;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("workflow succeeded via spec.retryStrategy", run.succeeded(), is(true));
                assertThat("3 attempts (spec.retryStrategy limit covers 2 retries)",
                        ((PodRun) run.entrypoint()).attempts(), is(3));
            }
        }
    }
}
