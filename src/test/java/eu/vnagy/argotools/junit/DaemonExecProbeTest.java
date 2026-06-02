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
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.WorkflowReleaseGate;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

class DaemonExecProbeTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Verifies the RUNNING → DAEMONED transition is gated by the exec probe.
     *
     * <p>The daemon polls the gate and only writes {@code /tmp/ready} once it opens.
     * The exec probe tests for that file. The test asserts that the daemon is NOT
     * daemoned before the gate is released, then becomes daemoned after.
     */
    @Test
    void execProbeGatesDaemonReadiness() throws Exception {
        try (var gate = new WorkflowReleaseGate()) {
            Workflow wf = YAML.readValue(
                    getClass().getResource("/daemon-exec-probe.yaml"), Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "gate_port".equals(p.getName()))
                    .findFirst().orElseThrow()
                    .setValue(String.valueOf(gate.port()));

            WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
            StepsRun main = (StepsRun) live.entrypoint();
            PodRun daemon = (PodRun) main.get("file-daemon");

            // Wait until the daemon container has started (RUNNING = inside cont.start(),
            // wait strategy still polling — the file hasn't been written yet because the gate is closed)
            long deadline = System.currentTimeMillis() + 30_000;
            while (!daemon.running()) {
                if (System.currentTimeMillis() > deadline) fail("daemon did not enter RUNNING state within 30s");
                Thread.sleep(100);
            }
            assertThat("daemon not yet daemoned before gate release", daemon.daemoned(), is(false));

            // Open the gate: the container writes /tmp/ready and the exec probe passes
            gate.release();

            deadline = System.currentTimeMillis() + 30_000;
            while (!daemon.daemoned() && !daemon.errored()) {
                if (System.currentTimeMillis() > deadline) fail("daemon did not become DAEMONED within 30s after gate release");
                Thread.sleep(100);
            }
            assertThat("daemon is daemoned after gate release", daemon.daemoned(), is(true));

            live.await();

            assertThat(live.succeeded(), is(true));
            assertThat("daemon stopped after workflow completion", daemon.isDaemonStopped(), is(true));
            assertThat(((PodRun) main.get("client")).succeeded(), is(true));
        }
    }

    /**
     * Verifies that a daemon whose exec probe never passes within
     * {@code failureThreshold × periodSeconds} ends up ERRORED and fails the workflow.
     */
    @Test
    void daemonNotReadyInTimeIsErrored() throws Exception {
        // failureThreshold: 3, periodSeconds: 1 → 3-second probe timeout
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: daemon-exec-timeout-
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: sleepy-daemon
                            template: sleepy-daemon
                    - name: sleepy-daemon
                      daemon: true
                      container:
                        image: alpine:3.21
                        command: [sleep, "300"]
                        readinessProbe:
                          exec:
                            command: [test, -f, /tmp/never]
                          periodSeconds: 1
                          failureThreshold: 3
                """;

        try (WorkflowRun run = ArgoWorkflowExecutor.from(yaml).execute()) {
            assertThat("workflow failed due to probe timeout", run.succeeded(), is(false));
            PodRun daemon = (PodRun) ((StepsRun) run.entrypoint()).get("sleepy-daemon");
            assertThat("daemon errored on probe timeout", daemon.errored(), is(true));
        }
    }
}
