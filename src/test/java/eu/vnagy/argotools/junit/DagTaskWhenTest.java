package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class DagTaskWhenTest {

    @Test
    void onlyMatchingTaskRuns() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper()
                .readValue(Path.of(getClass().getResource("/dag-task-when.yaml").toURI()).toFile(),
                        Workflow.class);
        setParam(wf, "mode", "a");

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            PodRun runA = (PodRun) dag.get("run-a");
            assertThat("run-a ran",    runA.succeeded(), is(true));
            assertThat("run-a output", runA.logs().trim(), containsString("ran-a"));

            PodRun runB = (PodRun) dag.get("run-b");
            assertThat("run-b omitted", runB.omitted(), is(true));
        }
    }

    private static void setParam(Workflow wf, String name, String value) {
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> p.getName().equals(name))
                .findFirst()
                .ifPresent(p -> p.setValue(value));
    }
}
