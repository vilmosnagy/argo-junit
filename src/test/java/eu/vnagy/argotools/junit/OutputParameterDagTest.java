package eu.vnagy.argotools.junit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class OutputParameterDagTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void outputParameterPassedBetweenDagTasks() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/output-parameter-dag.yaml"), Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
            assertThat(run.succeeded(), is(true));
            DagRun dag = (DagRun) run.entrypoint();
            PodRun consumer = (PodRun) dag.get("consume-parameter");
            assertThat(consumer.logs(), containsString("hello world"));
        }
    }
}
