package io.github.argoproj.argoworkflows;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class RetryTest {

    @Test
    void retryContainer() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/retry-container.yaml").toURI()))
                .execute()) {

            assertThat("workflow either succeeded or not after retries", run.succeeded(), oneOf(true, false));
            PodRun pod = (PodRun) run.entrypoint();
            assertThat("attempts recorded", pod.attempts(), greaterThan(0));
            assertThat("at most 11 attempts (1 initial + limit 10)", pod.attempts(), lessThanOrEqualTo(11));
            if (!run.succeeded()) {
                assertThat("workflow failed so max attempts should've been tried", pod.attempts(), equalTo(11));
            }
        }
    }

    @Test
    void retryBackoff() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/retry-backoff.yaml").toURI()))
                .execute()) {

            assertThat("workflow either succeeded or not after retries with backoff", run.succeeded(), oneOf(true, false));
            PodRun pod = (PodRun) run.entrypoint();
            assertThat("at most 11 attempts (1 initial + limit 10)", pod.attempts(), lessThanOrEqualTo(11));
            if (!run.succeeded()) {
                assertThat("workflow failed so max attempts should've been tried", pod.attempts(), equalTo(11));
            }
        }
    }

    @Test
    void retryToCompletion() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/retry-container-to-completion.yaml").toURI()))
                .execute()) {

            assertThat("workflow eventually succeeded with unlimited retries", run.succeeded(), is(true));
            PodRun pod = (PodRun) run.entrypoint();
            assertThat("at least one attempt", pod.attempts(), greaterThan(0));
        }
    }

    @Test
    void retryOnError() throws Exception {
        // retryPolicy: Always, limit: 2 → max 3 total attempts (1 initial + 2 retries).
        // Script fails 80% per run; with only 3 chances this may or may not succeed —
        // the test just verifies the limit is respected.
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/retry-on-error.yaml").toURI()))
                .execute()) {

            PodRun pod = (PodRun) run.entrypoint();
            assertThat("attempts recorded", pod.attempts(), greaterThan(0));
            assertThat("at most 3 attempts (1 initial + limit 2)", pod.attempts(), lessThanOrEqualTo(3));
        }
    }
}
