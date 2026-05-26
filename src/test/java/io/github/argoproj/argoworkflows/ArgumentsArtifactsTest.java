package io.github.argoproj.argoworkflows;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

class ArgumentsArtifactsTest {

    @Test
    void argumentsArtifacts() throws Exception {
        var executor = ArgoWorkflowExecutor.from(
                Path.of(getClass().getResource("/examples/arguments-artifacts.yaml").toURI()));

        // Start kwok — the kubectl binary inside the container will use it as the API server
        executor.getKubernetesClient();

        try (WorkflowRun run = executor.execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(run.entrypoint(), instanceOf(PodRun.class));
            // kubectl version prints both client and server version when the API server is reachable
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("Server Version"));
        }
    }
}
