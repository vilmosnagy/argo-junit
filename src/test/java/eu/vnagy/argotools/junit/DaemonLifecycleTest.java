package eu.vnagy.argotools.junit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowNode;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BiFunction;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

class DaemonLifecycleTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void daemonStoppedAfterStepsLayer2Completes() throws Exception {
        runDaemonTest("/daemon-steps-e2e.yaml",
                (node, name) -> ((StepsRun) node).get(name));
    }

    @Test
    void daemonStoppedAfterDagLayer2Completes() throws Exception {
        runDaemonTest("/daemon-dag-e2e.yaml",
                (node, name) -> ((DagRun) node).get(name));
    }

    private void runDaemonTest(String yamlResource,
            BiFunction<WorkflowNode, String, WorkflowNode> getChild) throws Exception {
        try (var gate = new WorkflowReleaseGate()) {
            Workflow wf = YAML.readValue(getClass().getResource(yamlResource), Workflow.class);
            String message = paramValue(wf, "message");
            setParam(wf, "release_port", String.valueOf(gate.port()));

            WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
            WorkflowNode main = live.entrypoint();

            // Wait until the wait node starts — meaning layer2 (and its daemon) have finished
            long deadline = System.currentTimeMillis() + 120_000;
            while (!getChild.apply(main, "wait").running()) {
                if (System.currentTimeMillis() > deadline) fail("wait did not start within 120s");
                Thread.sleep(100);
            }

            WorkflowNode layer2 = getChild.apply(main, "layer2");
            assertThat("layer2 succeeded", layer2.succeeded(), is(true));

            PodRun daemon = (PodRun) getChild.apply(layer2, "md5-server");
            assertThat("daemon reached daemoned state", daemon.daemoned(), is(true));
            assertThat("daemon stopped before wait started", daemon.isDaemonStopped(), is(true));

            gate.release();
            live.await();

            assertThat("workflow succeeded", live.succeeded(), is(true));

            PodRun curlPod = (PodRun) getChild.apply(layer2, "curl-md5");
            assertThat("curl output matches md5 of message",
                    curlPod.logs().trim(), is(md5Hex(message)));
        }
    }

    private static String paramValue(Workflow wf, String name) {
        return wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst().orElseThrow().getValue();
    }

    private static void setParam(Workflow wf, String name, String value) {
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(value);
    }

    private static String md5Hex(String input) throws Exception {
        byte[] hash = MessageDigest.getInstance("MD5")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
