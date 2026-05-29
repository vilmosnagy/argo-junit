package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflow).execute()) {
            assertTrue(run.succeeded(),
                    "script template command must override the image ENTRYPOINT, not append to it");
        }
    }
}
