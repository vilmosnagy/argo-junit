package io.github.argoproj.argoworkflows;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests env[].valueFrom.secretKeyRef using the upstream secrets.yaml example.
 * The workflow also declares a volumeMount (not yet supported); the test only
 * asserts the env-var path since the script exits 0 even when the mount is absent.
 */
class SecretEnvTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static KwokContainer kwok;

    @BeforeAll
    static void setup() {
        kwok = new KwokContainer();
        kwok.start();

        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(new SecretBuilder()
                        .withNewMetadata().withName("my-secret").endMetadata()
                        .withData(Map.of(
                                "mypassword",
                                Base64.getEncoder().encodeToString("S00perS3cretPa55word".getBytes())))
                        .build())
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
    }

    @Test
    void envVarFromSecret() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/secrets.yaml"), Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("secret from env: S00perS3cretPa55word"));
        }
    }
}
