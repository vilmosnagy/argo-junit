package io.github.argoproj.argoworkflows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests workflow and template parameters sourced from ConfigMap keys.
 * Covers three upstream example workflows that all depend on the "simple-parameters" ConfigMap.
 */
class ConfigMapTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static KwokContainer kwok;

    @BeforeAll
    static void setup() {
        kwok = new KwokContainer();
        kwok.start();

        kwok.createClient()
                .configMaps()
                .inNamespace("default")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("simple-parameters").endMetadata()
                        .withData(Map.of("msg", "hello world"))
                        .build())
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
    }

    /** inputs.parameters[].valueFrom.configMapKeyRef — template receives value at invocation time. */
    @Test
    void inputParameterFromConfigMap() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/arguments-parameters-from-configmap.yaml"), Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("hello world"));
        }
    }

    /** workflow.arguments.parameters[].valueFrom.configMapKeyRef — resolved into {{workflow.parameters.X}}. */
    @Test
    void workflowParameterFromConfigMap() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/global-parameters-from-configmap.yaml"), Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("hello world"));
        }
    }

    /**
     * workflow.arguments.parameters[].valueFrom.configMapKeyRef where the resolved value is then
     * referenced as {{workflow.parameters.X}} and forwarded as a template input parameter.
     */
    @Test
    void workflowParameterFromConfigMapReferencedAsLocalVariable() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource(
                        "/examples/global-parameters-from-configmap-referenced-as-local-variable.yaml"),
                Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("hello world"));
        }
    }
}
