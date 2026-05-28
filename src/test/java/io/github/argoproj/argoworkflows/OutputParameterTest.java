package io.github.argoproj.argoworkflows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class OutputParameterTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void outputParameterPassedBetweenSteps() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/output-parameter.yaml"), Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
            assertThat(run.succeeded(), is(true));
            StepsRun steps = (StepsRun) run.entrypoint();
            PodRun consumer = (PodRun) steps.get("consume-parameter");
            assertThat(consumer.logs(), containsString("hello world"));
        }
    }
}
