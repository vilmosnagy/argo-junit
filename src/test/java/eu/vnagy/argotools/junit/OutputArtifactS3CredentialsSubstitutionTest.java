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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/**
 * Verifies that output artifact S3 credentials are substituted before upload.
 *
 * <p>Before the fix, {@code PodRun} passed the raw {@code spec.artifact()} — containing
 * unresolved {@code {{workflow.parameters.*}}} placeholders — to
 * {@code S3ArtifactDriver.upload}. The driver tried to look up a Kubernetes secret
 * literally named {@code {{workflow.parameters.s3-secret-name}}}, which failed silently
 * (WARN log only), so the workflow appeared to succeed while the S3 key was never written.
 */
class OutputArtifactS3CredentialsSubstitutionTest {

    static final String BUCKET        = "output-artifact-creds-test";
    static final String SECRET_NAME   = "minio-creds-output";
    static final String OUTPUT_KEY    = "test/output.txt";
    static final String OUTPUT_KEY_2  = "test/output-keyparam.txt";
    static final String OUTPUT_KEY_3  = "test/output-dag-unreferenced.txt";

    static KwokContainer  kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setup() {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);

        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret(SECRET_NAME, "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok  != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    @Test
    void outputArtifactIsUploadedWhenCredentialsUseWorkflowParameters() throws Exception {
        String yaml = String.format("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: output-artifact-creds-substitution-test
                spec:
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: '%s'
                      - name: s3-bucket
                        value: '%s'
                      - name: s3-secret-name
                        value: '%s'
                  entrypoint: produce
                  templates:
                    - name: produce
                      script:
                        image: alpine:3
                        command: [sh]
                        source: echo hello-argo > /tmp/out.txt
                      outputs:
                        artifacts:
                          - name: result
                            path: /tmp/out.txt
                            archive:
                              none: {}
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: '%s'
                              accessKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: secret-key
                """, minio.endpoint(), BUCKET, SECRET_NAME, OUTPUT_KEY);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(), "Workflow must succeed before checking the upload");
        }

        try (S3Client client = minio.createClient()) {
            byte[] content = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(OUTPUT_KEY).build())
                    .asByteArray();
            assertThat(new String(content, StandardCharsets.UTF_8).trim(), is("hello-argo"));
        }
    }

    @Test
    void outputArtifactIsUploadedWhenKeyAlsoUsesWorkflowParameter() throws Exception {
        String yaml = String.format("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: output-artifact-key-substitution-test
                spec:
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: '%s'
                      - name: s3-bucket
                        value: '%s'
                      - name: s3-secret-name
                        value: '%s'
                      - name: s3-output-key
                        value: '%s'
                  entrypoint: produce
                  templates:
                    - name: produce
                      script:
                        image: alpine:3
                        command: [sh]
                        source: echo hello-key-param > /tmp/out.txt
                      outputs:
                        artifacts:
                          - name: result
                            path: /tmp/out.txt
                            archive:
                              none: {}
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: '{{workflow.parameters.s3-output-key}}'
                              accessKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: secret-key
                """, minio.endpoint(), BUCKET, SECRET_NAME, OUTPUT_KEY_2);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(), "Workflow must succeed before checking the upload");
        }

        try (S3Client client = minio.createClient()) {
            byte[] content = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(OUTPUT_KEY_2).build())
                    .asByteArray();
            assertThat(new String(content, StandardCharsets.UTF_8).trim(), is("hello-key-param"));
        }
    }

    /**
     * Reproduces the case where the producing task lives inside a DAG and no downstream task
     * references its output via {@code from:}. In that situation {@code DagRun} sets
     * {@code requestedOutputArtifacts} to {@code Set.of()} (non-null empty set), which causes
     * the filter in {@code PodRun} to skip the artifact unless it explicitly checks for an
     * external-storage driver.
     */
    @Test
    void dagTaskS3OutputArtifactIsUploadedEvenWhenNotReferencedDownstream() throws Exception {
        String yaml = String.format("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-unreferenced-s3-output-test
                spec:
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: '%s'
                      - name: s3-bucket
                        value: '%s'
                      - name: s3-secret-name
                        value: '%s'
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: produce
                            template: writer
                          - name: side
                            template: noop
                    - name: writer
                      script:
                        image: alpine:3
                        command: [sh]
                        source: echo hello-dag-unreferenced > /tmp/out.txt
                      outputs:
                        artifacts:
                          - name: result
                            path: /tmp/out.txt
                            archive:
                              none: {}
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: '%s'
                              accessKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: secret-key
                    - name: noop
                      script:
                        image: alpine:3
                        command: [sh]
                        source: "true"
                """, minio.endpoint(), BUCKET, SECRET_NAME, OUTPUT_KEY_3);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(), "Workflow must succeed before checking the upload");
        }

        try (S3Client client = minio.createClient()) {
            byte[] content = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(OUTPUT_KEY_3).build())
                    .asByteArray();
            assertThat(new String(content, StandardCharsets.UTF_8).trim(), is("hello-dag-unreferenced"));
        }
    }
}
