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
import com.sun.net.httpserver.HttpServer;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.ItemRun;
import eu.vnagy.argotools.junit.executor.WorkflowNode;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * A DAG task fanned out with {@code withParam} must expose real per-item progress while it is
 * still running — not a single unlabelled placeholder that renders the referenced template's
 * own shape as one flat, all-pending entry.
 *
 * Workflow topology (withparam-loop-live.yaml):
 *
 *   process-each  (withParam over 3 items)
 *     ├─ [0] "first"   → pings /done, exits
 *     ├─ [1] "second"  → pings /done, exits
 *     └─ [2] "third"   → polls /ready, blocks until the test releases it
 *
 * While iteration [2] is held, iterations [0] and [1] have already succeeded, so a live query
 * must show three distinct per-item branches with differing statuses.
 */
class LiveLoopFanoutStatusTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void liveLoopTaskShowsPerItemProgressWhileStillRunning() throws Exception {
        HttpServer releaseServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = releaseServer.getAddress().getPort();

        AtomicBoolean released = new AtomicBoolean(false);
        AtomicInteger gatePolls = new AtomicInteger();
        Set<String> reportedDone = ConcurrentHashMap.newKeySet();

        releaseServer.createContext("/ready", exchange -> {
            gatePolls.incrementAndGet();
            boolean ready = released.get();
            byte[] body = (ready ? "OK" : "wait").getBytes();
            exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        releaseServer.createContext("/done", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            reportedDone.add(query == null ? "?" : query);
            byte[] body = "OK".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        releaseServer.start();

        Testcontainers.exposeHostPorts(port);

        Workflow wf = YAML.readValue(getClass().getResource("/withparam-loop-live.yaml"), Workflow.class);
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "release_port".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(String.valueOf(port));

        WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
        DagRun dag = (DagRun) live.entrypoint();

        // Captured inside the in-flight window — the nodes keep mutating afterwards,
        // so every live observation has to be frozen into a local before releasing the gate.
        // Because iteration [2] cannot finish until the test releases the gate, "exactly 2
        // succeeded, exactly 1 running" is a *stable* window, not a fleeting race — safe to
        // wait for precisely, and safe to assert an exact rendered snapshot against.
        String liveSummary = null;
        boolean reachedStableWindow = false;

        try {
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                WorkflowNode node = dag.get("process-each");
                if (node instanceof ItemRun item) {
                    List<WorkflowNode> iterations = item.children();
                    long succeeded = iterations.stream().filter(WorkflowNode::succeeded).count();
                    long running   = iterations.stream().filter(WorkflowNode::running).count();
                    if (succeeded == 2 && running == 1) {
                        reachedStableWindow = true;
                        liveSummary = WorkflowSummary.format(live);
                        break;
                    }
                }
                Thread.sleep(100);
            }
            if (!reachedStableWindow) {
                // Freeze whatever the live view did show, for the failure message below.
                liveSummary = WorkflowSummary.format(live);
            }
        } finally {
            released.set(true);
            live.await(Duration.ofMinutes(10));
            releaseServer.stop(0);
        }

        System.out.println("=== In-progress state ===");
        System.out.println(liveSummary);

        assertThat("fan-out reached the stable in-flight window (2 items succeeded, 1 running)\n" + liveSummary,
                reachedStableWindow, is(true));

        assertThat(normalizeDurations(liveSummary), equalTo("""
                Status:  Unknown

                STEP                     DURATION  MESSAGE
                 ◷ main
                 └─◷ process-each
                    ├─✔ process-each[0]  {duration}  {"label":"first","gated":"no"}  {cid}
                    ├─✔ process-each[1]  {duration}  {"label":"second","gated":"no"}  {cid}
                    └─◷ process-each[2]  {duration}  {"label":"third","gated":"yes"}
                """));

        assertThat("workflow succeeded", live.succeeded(), is(true));

        String finalSummary = WorkflowSummary.format(live);
        System.out.println("=== Final result ===");
        System.out.println(finalSummary);

        assertThat(normalizeDurations(finalSummary), equalTo("""
                Status:  Succeeded

                STEP                     DURATION  MESSAGE
                 ✔ main
                 └─✔ process-each
                    ├─✔ process-each[0]  {duration}  {"label":"first","gated":"no"}  {cid}
                    ├─✔ process-each[1]  {duration}  {"label":"second","gated":"no"}  {cid}
                    └─✔ process-each[2]  {duration}  {"label":"third","gated":"yes"}  {cid}
                """));
    }

    private static String normalizeDurations(String summary) {
        return summary
                .replaceAll("\\d+m \\d+s|\\d+s", "{duration}")
                .replaceAll("\\{duration} {2,}", "{duration}  ")
                .replaceAll("[0-9a-f]{12}", "{cid}");
    }
}
