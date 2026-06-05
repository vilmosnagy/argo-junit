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
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.MinioContainer;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/**
 * Verifies that a step/task argument artifact with a direct S3 source (no {@code from:}) is
 * downloaded and injected into the container.
 *
 * <p>Before the fix, {@code DagRun.resolveArtifactArgs} and {@code StepsRun.parseArtifactArgs}
 * only retained artifacts that carried a {@code from:} reference; artifacts with an explicit S3
 * location were silently dropped, so the file was never present in the container.
 */
class DirectS3ArtifactArgTest {

    static final String BUCKET = "direct-s3-artifact-test";

    static KwokContainer kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setup() {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);

        try (S3Client client = minio.createClient()) {
            client.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key("data/hello.txt").build(),
                    RequestBody.fromString("hello from s3"));
        }

        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret("minio-creds", "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    @Test
    void dagTaskDirectS3ArtifactIsDownloadedAndInjected() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-direct-s3-artifact-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: data/hello.txt
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "hello from s3" /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG task with direct S3 artifact argument must download the file into the container");
        }
    }

    @Test
    void stepsDirectS3ArtifactIsDownloadedAndInjected() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-direct-s3-artifact-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                  templates:
                    - name: main
                      steps:
                        - - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: data/hello.txt
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "hello from s3" /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Steps step with direct S3 artifact argument must download the file into the container");
        }
    }

    @Test
    void dagTaskMissingS3ObjectErrorsThePodWithClearMessage() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-missing-s3-object-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: data/does-not-exist.txt
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          cat /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertFalse(run.succeeded(), "Workflow must not succeed when S3 object is missing");
            assertTrue(run.errored(), "Workflow must be in errored state");
            assertThat(WorkflowSummary.format(run), containsString("The specified key does not exist"));
        }
    }

    @Test
    void stepsStepMissingS3ObjectErrorsThePodWithClearMessage() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-missing-s3-object-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                  templates:
                    - name: main
                      steps:
                        - - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: data/does-not-exist.txt
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          cat /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertFalse(run.succeeded(), "Workflow must not succeed when S3 object is missing");
            assertTrue(run.errored(), "Workflow must be in errored state");
            assertThat(WorkflowSummary.format(run), containsString("The specified key does not exist"));
        }
    }

    @Test
    void dagTaskInputsParamSubstitutedInS3ArtifactKey() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-inputs-param-s3-key-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                      - name: file-key
                        value: data/hello.txt
                  templates:
                    - name: main
                      steps:
                        - - name: run-dag
                            template: process
                            arguments:
                              parameters:
                                - name: file-key
                                  value: '{{workflow.parameters.file-key}}'
                    - name: process
                      inputs:
                        parameters:
                          - name: file-key
                      dag:
                        tasks:
                          - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: '{{inputs.parameters.file-key}}'
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "hello from s3" /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG task: {{inputs.parameters.*}} in S3 artifact key must be substituted before download");
        }
    }

    @Test
    void stepsInputsParamSubstitutedInS3ArtifactKey() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-inputs-param-s3-key-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                      - name: file-key
                        value: data/hello.txt
                  templates:
                    - name: main
                      steps:
                        - - name: run-steps
                            template: process
                            arguments:
                              parameters:
                                - name: file-key
                                  value: '{{workflow.parameters.file-key}}'
                    - name: process
                      inputs:
                        parameters:
                          - name: file-key
                      steps:
                        - - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: '{{inputs.parameters.file-key}}'
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "hello from s3" /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Steps step: {{inputs.parameters.*}} in S3 artifact key must be substituted before download");
        }
    }

    @Test
    void dagTaskOutputParamSubstitutedInS3ArtifactKey() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-task-output-param-s3-key-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: produce
                            template: produce
                          - name: consume
                            depends: produce
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: '{{tasks.produce.outputs.parameters.file-key}}'
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: produce
                      outputs:
                        parameters:
                          - name: file-key
                            valueFrom:
                              path: /tmp/key.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          printf 'data/hello.txt' > /tmp/key.txt
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "hello from s3" /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG task: {{tasks.X.outputs.parameters.*}} in S3 artifact key must be substituted before download");
        }
    }

    @Test
    void stepsOutputParamSubstitutedInS3ArtifactKey() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-output-param-s3-key-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                  templates:
                    - name: main
                      steps:
                        - - name: produce
                            template: produce
                        - - name: consume
                            template: consume
                            arguments:
                              artifacts:
                                - name: data-file
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: '{{steps.produce.outputs.parameters.file-key}}'
                                    accessKeySecret:
                                      name: minio-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-creds
                                      key: secret-key
                                  archive:
                                    none: {}
                    - name: produce
                      outputs:
                        parameters:
                          - name: file-key
                            valueFrom:
                              path: /tmp/key.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          printf 'data/hello.txt' > /tmp/key.txt
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "hello from s3" /data/input.txt
                """;

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Steps step: {{steps.X.outputs.parameters.*}} in S3 artifact key must be substituted before download");
        }
    }

    private Workflow patchEndpointAndBucket(String yaml) throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "s3-endpoint".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(minio.endpoint());
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "s3-bucket".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(BUCKET);
        return wf;
    }
}
