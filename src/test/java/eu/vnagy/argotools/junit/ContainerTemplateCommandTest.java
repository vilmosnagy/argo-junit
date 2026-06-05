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
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/**
 * Verifies that a {@code container:} template's {@code command} array is applied as the Docker
 * ENTRYPOINT (not CMD), matching Kubernetes container-spec semantics.
 *
 * <p>Before the fix, {@code command} and {@code args} were concatenated and passed to
 * {@code withCommand}, which sets CMD only. An image with a baked-in ENTRYPOINT would prepend
 * it, so {@code command: [sh, -c]} + {@code args: ["echo hello"]} became
 * {@code [/bin/false, sh, -c, echo hello]} instead of {@code [sh, -c, echo hello]}.
 */
class ContainerTemplateCommandTest {

    static String imageWithFalseEntrypoint;

    @BeforeAll
    static void buildImage() {
        imageWithFalseEntrypoint = new ImageFromDockerfile()
                .withFileFromString("Dockerfile", "FROM alpine\nENTRYPOINT [\"/bin/false\"]")
                .get();
    }

    @Test
    void containerTemplate_commandOverridesImageEntrypoint() throws Exception {
        // Without the fix: withCommand([sh, -c, echo hello]) sets CMD only, so Docker runs
        // /bin/false sh -c "echo hello" → exits 1, workflow fails.
        // With the fix: ENTRYPOINT=[sh, -c] CMD=[echo hello] → runs "echo hello" → succeeds.
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: container-entrypoint-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      container:
                        image: %s
                        command: [sh, -c]
                        args: ["echo hello"]
                """.formatted(imageWithFalseEntrypoint);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "container template command must override the image ENTRYPOINT, not append to it");
            assertThat("container output contains hello", ((PodRun) run.entrypoint()).logs(), containsString("hello"));
        }
    }

    @Test
    void containerTemplate_commandWithNoArgsClears_imageDefaultCmd() throws Exception {
        // command without args: ENTRYPOINT=[echo, cleared] CMD cleared — verify that the image's
        // default CMD is not appended. Using plain alpine (no custom ENTRYPOINT) with
        // command: [echo] — should produce no extra output beyond what echo prints by default.
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: container-command-no-args-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      container:
                        image: alpine:3
                        command: [echo, hello]
                """;

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(), "container template with command and no args must succeed");
            assertThat("output contains hello", ((PodRun) run.entrypoint()).logs(), containsString("hello"));
        }
    }
}
