package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class DaemonWorkflowTest {

    private ObjectMapper objectMapper;

    @Test
    void executesDaemonNginx() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/daemon-nginx.yaml").toURI()))
                .execute();

        assertThat(run.succeeded(), is(true));

        StepsRun steps = (StepsRun) run.entrypoint();

        PodRun nginx = (PodRun) steps.get("nginx-server");
        assertThat(nginx.daemoned(), is(true));
        assertThat(nginx.ip().isPresent(), is(true));

        PodRun client = (PodRun) steps.get("nginx-client");
        assertThat(client.succeeded(), is(true));
        assertThat(client.exitCode(), is(0));
        assertThat(client.logs(), containsString("Welcome to nginx!"));

        System.out.println(WorkflowSummary.format(run));
    }

    @Test
    void executesDaemonStep() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/daemon-step.yaml").toURI()))
                .execute();

        assertThat(run.succeeded(), is(true));

        StepsRun steps = (StepsRun) run.entrypoint();

        PodRun influx = (PodRun) steps.get("influx");
        assertThat(influx.daemoned(), is(true));
        assertThat(influx.ip().isPresent(), is(true));

        PodRun initDb = (PodRun) steps.get("init-database");
        assertThat(initDb.exitCode(), is(0));

        PodRun consumer = (PodRun) steps.get("consumer");
        assertThat(consumer.exitCode(), is(0));
        String logs = consumer.logs();
        assertThat(logs, containsString("cpu"));
        objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(logs).get("results").get(0).get("series").get(0).get("values").size(), is(21));

        System.out.println(WorkflowSummary.format(run));
    }
}
