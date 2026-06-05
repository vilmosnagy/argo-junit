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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import java.time.Duration;

/** Tests env[].valueFrom.configMapKeyRef — container env var injected from a ConfigMap key. */
class EnvFromConfigMapTest {

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
                        .withNewMetadata().withName("greetings").endMetadata()
                        .withData(Map.of("greeting", "hello from configmap"))
                        .build())
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
    }

    @Test
    void envVarFromConfigMap() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/env-from-configmap.yaml"), Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("greeting=hello from configmap"));
        }
    }
}
