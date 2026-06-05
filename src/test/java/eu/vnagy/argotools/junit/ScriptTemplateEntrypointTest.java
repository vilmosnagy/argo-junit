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
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/**
 * Verifies that a script template's {@code command} array is used as the container
 * ENTRYPOINT, overriding any ENTRYPOINT baked into the image.
 *
 * <p>Before the fix, {@code command} was appended to {@code /tmp/script} and passed as CMD,
 * so an image with a custom ENTRYPOINT (e.g. {@code /usr/local/bin/k8s-cm-kv}) would prepend
 * that binary, turning {@code sh -e /tmp/script} into {@code k8s-cm-kv sh -e /tmp/script}.
 */
class ScriptTemplateEntrypointTest {

    @Test
    void scriptTemplate_overridesImageEntrypoint() throws Exception {
        // Build a minimal image whose ENTRYPOINT is /bin/false. Without the fix the container
        // receives [/bin/false, sh, -c, /tmp/script], exits 1, and the workflow fails.
        // With the fix, ENTRYPOINT=[sh, -c] and CMD=[/tmp/script], so the script runs normally.
        String image = new ImageFromDockerfile()
                .withFileFromString("Dockerfile", "FROM alpine\nENTRYPOINT [\"/bin/false\"]")
                .get();

        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: entrypoint-override-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: %s
                        command: [sh]
                        source: echo hello
                """.formatted(image);

        Workflow workflow = ArgoWorkflowExecutor.yamlMapper().readValue(workflowYaml, Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflow).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "script template command must override the image ENTRYPOINT, not append to it");
        }
    }
}
