package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class UnlimitedRetryTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void templateDefaultsLimitActsAsSafetyNet() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/unlimited-retry.yaml").toURI()))
                .execute()) {

            assertThat("workflow failed", run.failed(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            PodRun runner = (PodRun) dag.get("run");
            // templateDefaults limit=2 → 3 total attempts (1 original + 2 retries)
            assertThat("attempts capped by templateDefaults",
                    runner.attempts(), is(3));
        }
    }
}
