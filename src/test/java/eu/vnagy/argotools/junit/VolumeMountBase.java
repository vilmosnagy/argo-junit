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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Shared fixture and test scenarios for ConfigMap and Secret volume-mount tests.
 *
 * <p>Concrete subclasses implement {@link #configure(ArgoWorkflowExecutor)} to wire in the
 * Docker daemon that step containers run against. The local variant leaves the executor
 * unchanged; the DinD variant routes step containers through a remote daemon to reproduce
 * the bind-mount visibility bug.
 */
public abstract class VolumeMountBase {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static KwokContainer kwok;

    @BeforeAll
    static void setUpKwok() {
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
    static void tearDownKwok() {
        if (kwok != null) kwok.stop();
    }

    protected abstract ArgoWorkflowExecutor configure(ArgoWorkflowExecutor executor);

    @Test
    void configMapVolumeIsProjectedIntoContainer() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/volume-configmap.yaml"), Workflow.class);
        try (WorkflowRun run = configure(ArgoWorkflowExecutor.from(wf).withKwok(kwok))
                .execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("Hello from ConfigMap"));
        }
    }

    @Test
    void secretVolumeAndEnvAreProjectedIntoContainer() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/secrets.yaml"), Workflow.class);
        try (WorkflowRun run = configure(ArgoWorkflowExecutor.from(wf).withKwok(kwok))
                .execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("S00perS3cretPa55word"));
        }
    }
}
