package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code env[].value} entries (plain string values, not {@code valueFrom:} refs)
 * are injected into the container.
 *
 * <p>Before the fix, {@code PodRun} skipped any env entry whose {@code valueFrom} was null,
 * so plain-value env vars were silently dropped and never reached the container.
 */
class EnvPlainValueTest {

    @Test
    void plainEnvVarsArePassedToContainer() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: plain-env-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - name: GREETING
                            value: hello-from-env
                        source: |
                          echo "$GREETING" > /tmp/out.txt
                      outputs:
                        parameters:
                          - name: result
                            valueFrom:
                              path: /tmp/out.txt
                """;

        Workflow workflow = ArgoWorkflowExecutor.yamlMapper().readValue(workflowYaml, Workflow.class);

        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflow).execute()) {
            assertTrue(run.succeeded(), "workflow must succeed — plain env var must reach the container");
            PodRun pod = (PodRun) run.entrypoint();
            assertEquals("hello-from-env", pod.collectedOutputParams().get("result"),
                    "output parameter must reflect the injected env var value");
        }
    }
}
