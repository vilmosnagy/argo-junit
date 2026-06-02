package eu.vnagy.argotools.junit.executor;

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

import eu.vnagy.argotools.junit.kwok.KwokContainer;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.*;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Demonstrates the bring-your-own-kwok pattern: a single KwokContainer is started once
 * for the test class, shared across multiple ArgoWorkflowExecutor instances via withKwok(),
 * and stopped only in @AfterAll. Closing an executor does not affect the shared container.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SharedKwokTest {

    static final String WORKFLOW = "/examples/arguments-parameters-from-configmap.yaml";

    static KwokContainer kwok;
    static KubernetesClient k8s;

    @BeforeAll
    static void startSharedKwok() {
        kwok = new KwokContainer();
        kwok.start();
        k8s = kwok.createClient();
        k8s.configMaps()
                .inNamespace("default")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("simple-parameters").endMetadata()
                        .addToData("msg", "shared value")
                        .build())
                .create();
    }

    @AfterAll
    static void stopSharedKwok() {
        if (kwok != null) kwok.stop();
    }

    @Test
    @Order(1)
    void executorBorrowsKwokWithoutOwningIt() throws Exception {
        var executor = ArgoWorkflowExecutor.from(
                Path.of(getClass().getResource(WORKFLOW).toURI()));

        executor.withKwok(kwok);

        try (WorkflowRun run = executor.execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(run.entrypoint(), instanceOf(PodRun.class));
            assertThat(((PodRun) run.entrypoint()).logs().strip(), is("shared value"));
        }
        // executor.close() was called by try-with-resources; kwok must still be running
        assertThat("kwok still reachable after executor.close()",
                k8s.configMaps().inNamespace("default").withName("simple-parameters").get(),
                notNullValue());
    }

    @Test
    @Order(2)
    void secondExecutorReusesSharedKwokWithNoStartupCost() throws Exception {
        try (var executor = ArgoWorkflowExecutor.from(
                Path.of(getClass().getResource(WORKFLOW).toURI())).withKwok(kwok);
             WorkflowRun run = executor.execute()) {
            assertThat(run.succeeded(), is(true));
        }
    }
}
