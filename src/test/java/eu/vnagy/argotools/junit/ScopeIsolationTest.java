package eu.vnagy.argotools.junit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that step outputs ({{steps.X.outputs.result}}) are scoped to the
 * sub-workflow that produced them and do not bleed across sibling sub-workflows
 * that happen to have a step with the same name.
 *
 * Topology (scope-isolation.yaml):
 *
 *   main (DAG)
 *   ├── child1 (steps): echo-pod → consumer1 → gate1 → consumer2
 *   └── child2 (steps): gate2 → echo-pod   ← same step name, different output
 *
 * Test choreography:
 *   1. child1/echo-pod finishes → test releases gate2
 *   2. child2/echo-pod runs ("completely-different") and finishes
 *   3. test releases gate1
 *   4. child1/consumer2 resolves {{steps.echo-pod.outputs.result}}
 *      → must see "hello-from-child1", not child2's value
 */
class ScopeIsolationTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void stepOutputsAreIsolatedBySubworkflowScope() throws Exception {
        try (var gate1 = new WorkflowReleaseGate();
             var gate2 = new WorkflowReleaseGate()) {

            Workflow wf = YAML.readValue(getClass().getResource("/scope-isolation.yaml"), Workflow.class);
            setParam(wf, "gate1_port", String.valueOf(gate1.port()));
            setParam(wf, "gate2_port", String.valueOf(gate2.port()));

            WorkflowRun live = ArgoWorkflowExecutor.from(wf).executeAsync();
            DagRun main = (DagRun) live.entrypoint();
            StepsRun child1 = (StepsRun) main.get("child1");
            StepsRun child2 = (StepsRun) main.get("child2");

            // Wait for child1/consumer1 to succeed — confirms child1/echo-pod has finished
            // and its output was correctly consumed at least once before child2 interferes.
            long deadline = System.currentTimeMillis() + 60_000;
            while (!child1.get("consumer1").succeeded()) {
                if (System.currentTimeMillis() > deadline) fail("consumer1 did not succeed within 60s");
                Thread.sleep(100);
            }

            // Release gate2: child2/echo-pod now runs with the same step name but a
            // completely different output value. If context is not scoped, it will
            // overwrite child1's "echo-pod" entry and poison consumer2's substitution.
            gate2.release();

            // Wait for child2/echo-pod to finish — the collision has happened (if bug exists).
            deadline = System.currentTimeMillis() + 60_000;
            while (!child2.get("echo-pod").succeeded()) {
                if (System.currentTimeMillis() > deadline) fail("child2/echo-pod did not succeed within 60s");
                Thread.sleep(100);
            }

            // Release gate1: child1/consumer2 will now start and substitute
            // {{steps.echo-pod.outputs.result}} from the context.
            // Bug: gets "completely-different" (child2 overwrote the shared map).
            // Fix: gets "hello-from-child1" (each sub-workflow has its own scope).
            gate1.release();

            live.await();
            assertThat("workflow succeeded", live.succeeded(), is(true));

            PodRun consumer1 = (PodRun) child1.get("consumer1");
            PodRun consumer2 = (PodRun) child1.get("consumer2");

            assertThat("consumer1 received child1/echo-pod output",
                    consumer1.outputResult().orElse("").trim(), is("hello-from-child1"));
            assertThat("consumer2 received child1/echo-pod output, not child2's same-named step",
                    consumer2.outputResult().orElse("").trim(), is("hello-from-child1"));
        }
    }

    private static void setParam(Workflow wf, String name, String value) {
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(value);
    }
}
