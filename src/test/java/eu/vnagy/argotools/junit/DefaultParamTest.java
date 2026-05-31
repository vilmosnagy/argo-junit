package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class DefaultParamTest {

    @Test
    void dagMissingParameterFallsBackToTemplateDefault() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/dag-default-param.yaml").toURI()))
                .execute()) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();
            PodRun runner = (PodRun) dag.get("run");
            assertThat("runner succeeded", runner.succeeded(), is(true));
            assertThat("output uses default separator",
                    runner.logs().trim(), containsString("a, b, c"));
        }
    }

    @Test
    void stepsMissingParameterFallsBackToTemplateDefault() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/steps-default-param.yaml").toURI()))
                .execute()) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            StepsRun steps = (StepsRun) run.entrypoint();
            PodRun runner = (PodRun) steps.get("run");
            assertThat("runner succeeded", runner.succeeded(), is(true));
            assertThat("output uses default separator",
                    runner.logs().trim(), containsString("a, b, c"));
        }
    }
}
