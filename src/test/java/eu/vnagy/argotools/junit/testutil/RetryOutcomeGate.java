package eu.vnagy.argotools.junit.testutil;

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

import com.sun.net.httpserver.HttpServer;
import org.testcontainers.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * An HTTP gate that dispenses scripted outcomes to workflow containers one at a time.
 *
 * <p>Containers poll {@code http://host.docker.internal:<port>/outcome}, which returns:
 * <ul>
 *   <li>{@code "succeed"} — the container should exit 0
 *   <li>{@code "fail"}    — the container should exit 1
 *   <li>{@code "wait"}    — no outcome queued yet; container should poll again
 * </ul>
 * Each queued outcome is consumed by exactly one poll, enabling deterministic
 * control of per-attempt results from the test.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var gate = new RetryOutcomeGate()) {
 *     gate.willFail();
 *     gate.willFail();
 *     gate.willSucceed();
 *     setParam(wf, "port", String.valueOf(gate.port()));
 *     try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
 *         assertThat(run.succeeded(), is(true));
 *         assertThat(((PodRun) run.entrypoint()).attempts(), is(3));
 *     }
 * }
 * }</pre>
 */
public final class RetryOutcomeGate implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final ConcurrentLinkedQueue<Boolean> outcomes = new ConcurrentLinkedQueue<>();

    public RetryOutcomeGate() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/outcome", exchange -> {
            Boolean outcome = outcomes.poll();
            String body = outcome == null ? "wait" : outcome ? "succeed" : "fail";
            byte[] bytes = body.getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        Testcontainers.exposeHostPorts(port);
    }

    /** Host port reachable from containers as {@code host.docker.internal:<port>}. */
    public int port() { return port; }

    /** Queues one attempt outcome: the container will exit 1. */
    public void willFail() { outcomes.add(false); }

    /** Queues one attempt outcome: the container will exit 0. */
    public void willSucceed() { outcomes.add(true); }

    @Override
    public void close() { server.stop(0); }
}
