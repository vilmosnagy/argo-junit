package eu.vnagy.argotools.junit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.sun.net.httpserver.HttpServer;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests that a running {@link WorkflowRun} reflects in-progress state correctly by walking
 * the live tree directly — no separate snapshot mechanism.
 *
 * Workflow topology (delayed-dag.yaml):
 *
 *   fast-start ──────────────────────────────────────► [done]
 *   slow-start (polls HTTP /ready until released) ──► fast-b
 *                                                 └──► fast-c
 *
 * The test spins up an embedded Java HTTP server and passes its port as
 * workflow parameter "release_port". slow-start's Python script polls the
 * server; it stays RUNNING until the test sets the release flag.
 *
 * Expected state while slow-start is held:
 *   fast-start  SUCCEEDED  — no dependencies, finishes before slow-start even starts
 *   slow-start  RUNNING    — blocked on HTTP poll
 *   fast-b      PENDING    — depends on slow-start
 *   fast-c      PENDING    — depends on slow-start
 */
class LiveWorkflowRunTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void liveTreeShowsInProgressState() throws Exception {
        HttpServer releaseServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = releaseServer.getAddress().getPort();
        AtomicBoolean released = new AtomicBoolean(false);
        releaseServer.createContext("/ready", exchange -> {
            boolean ready = released.get();
            byte[] body = (ready ? "OK" : "wait").getBytes();
            exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        releaseServer.start();

        Testcontainers.exposeHostPorts(port);

        Workflow wf = YAML.readValue(getClass().getResource("/delayed-dag.yaml"), Workflow.class);
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "release_port".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(String.valueOf(port));

        WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
        DagRun dag = (DagRun) live.entrypoint();

        // Poll until the interesting steady-state: slow-start blocking on HTTP AND fast-start done
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            boolean slowRunning = dag.get("slow-start").running();
            boolean fastDone    = dag.get("fast-start").succeeded();
            if (slowRunning && fastDone) break;
            if (System.currentTimeMillis() > deadline) fail("did not reach expected state within 30s");
            Thread.sleep(100);
        }

        // fast-start has no dependencies — it races slow-start and wins
        assertThat("fast-start should be done",    ((PodRun) dag.get("fast-start")).succeeded(), is(true));
        assertThat("slow-start should be running", dag.get("slow-start").running(),               is(true));
        assertThat("fast-b should be pending",     dag.get("fast-b").pending(),                   is(true));
        assertThat("fast-c should be pending",     dag.get("fast-c").pending(),                   is(true));

        System.out.println("=== In-progress state ===");
        System.out.println(WorkflowSummary.format(live));

        released.set(true); // unblock slow-start; next HTTP poll returns 200

        live.await();
        assertThat(live.succeeded(), is(true));

        System.out.println("=== Final result ===");
        System.out.println(WorkflowSummary.format(live));

        releaseServer.stop(0);
    }
}
