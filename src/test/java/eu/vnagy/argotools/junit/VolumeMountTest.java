package eu.vnagy.argotools.junit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class VolumeMountTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static KwokContainer kwok;

    @BeforeAll
    static void setup() {
        kwok = new KwokContainer();
        kwok.start();
        var k8s = kwok.createClient();

        k8s.configMaps().inNamespace("default").resource(
                new ConfigMapBuilder()
                        .withNewMetadata().withName("my-app-config").endMetadata()
                        .withData(Map.of("greeting", "Hello from ConfigMap", "farewell", "Goodbye from ConfigMap"))
                        .build()).create();

        String encoded = Base64.getEncoder().encodeToString("S00perS3cretPa55word".getBytes());
        k8s.secrets().inNamespace("default").resource(
                new SecretBuilder()
                        .withNewMetadata().withName("my-secret").endMetadata()
                        .withData(Map.of("mypassword", encoded))
                        .build()).create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
    }

    @Test
    void configMapVolumeIsProjectedIntoContainer() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/volume-configmap.yaml"), Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
            // The container cats /config/greeting and /config/farewell
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("Hello from ConfigMap"));
        }
    }

    @Test
    void secretVolumeAndEnvAreProjectedIntoContainer() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/secrets.yaml"), Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("S00perS3cretPa55word"));
        }
    }
}
