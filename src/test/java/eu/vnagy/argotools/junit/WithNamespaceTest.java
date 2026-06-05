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

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

class WithNamespaceTest {

    @Test
    void configMapReadFromCustomNamespace() throws Exception {
        var executor = ArgoWorkflowExecutor.from(
                Path.of(getClass().getResource("/examples/arguments-parameters-from-configmap.yaml").toURI()));

        var k8s = executor.getKubernetesClient();

        // Same ConfigMap name in default namespace — must not be picked up
        k8s.configMaps()
                .inNamespace("default")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("simple-parameters").endMetadata()
                        .addToData("msg", "wrong namespace")
                        .build())
                .create();

        // Same ConfigMap name in the custom namespace — this is the one we expect
        String customNs = "argotest";
        k8s.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata().withName(customNs).endMetadata()
                        .build())
                .create();
        k8s.configMaps()
                .inNamespace(customNs)
                .resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("simple-parameters").endMetadata()
                        .addToData("msg", "custom namespace value")
                        .build())
                .create();

        executor.withNamespace(customNs);
        try (WorkflowRun run = executor.execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            assertThat(run.entrypoint(), instanceOf(PodRun.class));
            assertThat(((PodRun) run.entrypoint()).logs().strip(), is("custom namespace value"));
        }
    }
}
