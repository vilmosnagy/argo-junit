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
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/**
 * Verifies that volumes declared under {@code template.volumes} (template-level scope)
 * are materialized and mounted just like {@code spec.volumes}.
 *
 * <p>Before the fix, only {@code spec.volumes} was consulted; a volume declared on the
 * template was silently missing and any {@code volumeMounts} referencing it had nothing
 * to bind, causing the container to fail with "No such file or directory".
 */
class TemplateLevelVolumeTest {

    @Test
    void templateLevelConfigMapVolumeIsMounted() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: template-volume-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      volumes:
                        - name: data-vol
                          configMap:
                            name: test-cm
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        volumeMounts:
                          - name: data-vol
                            mountPath: /data
                        source: |
                          cat /data/hello > /tmp/result.txt
                      outputs:
                        parameters:
                          - name: result
                            valueFrom:
                              path: /tmp/result.txt
                """;

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(workflowYaml, Workflow.class);

        try (ArgoWorkflowExecutor executor = ArgoWorkflowExecutor.from(wf)) {
            KubernetesClient k8s = executor.getKubernetesClient();
            k8s.configMaps().inNamespace("default").resource(
                    new ConfigMapBuilder()
                            .withNewMetadata().withName("test-cm").endMetadata()
                            .withData(Map.of("hello", "world"))
                            .build()
            ).create();

            try (WorkflowRun run = executor.execute(Duration.ofMinutes(10))) {
                assertTrue(run.succeeded(), "template-level configMap volume must be mounted");
                PodRun pod = (PodRun) run.entrypoint();
                assertEquals("world", pod.collectedOutputParams().get("result"),
                        "volume file content must match the ConfigMap entry");
            }
        }
    }
}
