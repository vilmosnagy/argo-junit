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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that a workflow read back from kwok can be serialized via the fabric8 client and
 * re-parsed by {@link ArgoWorkflowExecutor#from(String)}.
 *
 * <p>When kwok stores the resource it adds server-side metadata ({@code creationTimestamp},
 * {@code resourceVersion}, {@code uid}). The fabric8 serializer then emits those fields as
 * typed values — {@code creationTimestamp} becomes an ISO-8601 string — and Jackson must
 * handle {@link java.time.OffsetDateTime} to deserialize it.
 */
class KwokRoundTripTest {

    static final ResourceDefinitionContext WORKFLOW_CTX = new ResourceDefinitionContext.Builder()
            .withGroup("argoproj.io").withVersion("v1alpha1").withKind("Workflow")
            .withNamespaced(true).build();

    static ArgoKwok argoKwok;
    static KubernetesClient k8s;

    @BeforeAll
    static void setUp() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        k8s = argoKwok.createClient();
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void workflowRoundTrippedThroughKwokCanBeExecuted() throws Exception {
        String originalYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: kwok-roundtrip-test
                  namespace: default
                spec:
                  entrypoint: echo
                  templates:
                  - name: echo
                    container:
                      image: alpine:3
                      command: [echo, hello]
                """;

        // Apply to kwok
        GenericKubernetesResource gkr = k8s.getKubernetesSerialization()
                .unmarshal(originalYaml, GenericKubernetesResource.class);
        k8s.genericKubernetesResources(WORKFLOW_CTX)
                .inNamespace("default")
                .resource(gkr)
                .create();

        // Read back — kwok adds creationTimestamp, resourceVersion, uid
        GenericKubernetesResource readBack = k8s.genericKubernetesResources(WORKFLOW_CTX)
                .inNamespace("default")
                .withName("kwok-roundtrip-test")
                .get();

        // Serialize via fabric8 (this is the user's code path)
        String yaml = k8s.getKubernetesSerialization().asYaml(readBack);

        // Parse and execute — previously failed with:
        //   InvalidDefinitionException: Java 8 date/time type `OffsetDateTime` not supported
        try (WorkflowRun run = ArgoWorkflowExecutor.from(yaml)
                .withKwok(argoKwok.container())
                .execute(Duration.ofMinutes(5))) {
            assertThat(run.succeeded(), is(true));
        }
    }
}
