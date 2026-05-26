package eu.vnagy.argotools.junit.testutil;

import com.sun.net.httpserver.HttpServer;
import org.testcontainers.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An HTTP gate that workflow containers can poll until the test releases it.
 *
 * <p>Containers reach it via {@code host.docker.internal:<port>/ready}, which
 * returns 503 while held and 200 once {@link #release()} is called. The port is
 * automatically exposed to containers via Testcontainers on construction.
 *
 * <p>Usage:
 * <pre>{@code
 * try (var gate = new WorkflowReleaseGate()) {
 *     setParam(wf, "release_port", String.valueOf(gate.port()));
 *     // ... start workflow, wait for some condition ...
 *     gate.release();
 *     live.await();
 * }
 * }</pre>
 */
public final class WorkflowReleaseGate implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final AtomicBoolean released = new AtomicBoolean(false);

    public WorkflowReleaseGate() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ready", exchange -> {
            boolean ready = released.get();
            byte[] body = (ready ? "OK" : "wait").getBytes();
            exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        Testcontainers.exposeHostPorts(port);
    }

    /** Host port reachable from containers as {@code host.docker.internal:<port>}. */
    public int port() { return port; }

    /** Unblocks the next /ready poll, causing any waiting container to proceed. */
    public void release() { released.set(true); }

    @Override
    public void close() { server.stop(0); }
}
