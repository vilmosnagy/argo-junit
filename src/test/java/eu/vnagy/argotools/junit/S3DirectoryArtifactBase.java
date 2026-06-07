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
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.MinioContainer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixture and test scenarios for S3 directory artifact tests.
 *
 * <p>Concrete subclasses implement {@link #configure(ArgoWorkflowExecutor)} to wire in the
 * Docker daemon that step containers run against. The default (local) variant leaves the
 * executor unchanged; the DinD variant adds a remote daemon client so the test exercises the
 * code path where the JVM and the Docker daemon do not share a filesystem.
 */
public abstract class S3DirectoryArtifactBase {

    static final String BUCKET      = "s3-dir-artifact-test";
    static final String SECRET_NAME = "minio-dir-creds";

    static KwokContainer  kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setUpS3() throws Exception {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);

        // Flat tar.gz simulating a directory archive created with:
        //   tar czf archive.tar.gz -C /dir .
        // Root-level entries (no enclosing directory): .dir-archive + a.txt
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);

            TarArchiveEntry sentinel = new TarArchiveEntry(".dir-archive");
            sentinel.setSize(0);
            tar.putArchiveEntry(sentinel);
            tar.closeArchiveEntry();

            byte[] fileContent = "hello directory".getBytes();
            TarArchiveEntry file = new TarArchiveEntry("a.txt");
            file.setSize(fileContent.length);
            tar.putArchiveEntry(file);
            tar.write(fileContent);
            tar.closeArchiveEntry();
        }

        try (S3Client client = minio.createClient()) {
            client.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key("data/dir.tar.gz").build(),
                    RequestBody.fromBytes(baos.toByteArray()));
        }

        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret(SECRET_NAME, "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDownS3() {
        if (kwok  != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    /**
     * Called by each test method to give subclasses a chance to configure the executor.
     * The local variant returns the executor unchanged; the DinD variant adds
     * {@code withDockerClient(...)} to route step containers through a remote daemon.
     */
    protected abstract ArgoWorkflowExecutor configure(ArgoWorkflowExecutor executor);

    @Test
    void stepsStepReceivesS3DirectoryArtifactAsDirectory() throws Exception {
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: s3-dir-artifact-steps-test
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
                        - - name: read-dir
                            template: read-dir
                            arguments:
                              artifacts:
                                - name: dir-data
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: data/dir.tar.gz
                                    accessKeySecret:
                                      name: minio-dir-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-dir-creds
                                      key: secret-key
                    - name: read-dir
                      inputs:
                        artifacts:
                          - name: dir-data
                            path: /input
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test -d /input
                          ls -a /input
                """;

        Workflow wf = patchEndpointAndBucket(yaml);
        try (WorkflowRun run = configure(ArgoWorkflowExecutor.from(wf).withKwok(kwok))
                .execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(), "workflow must succeed — /input must be a directory");
            StepsRun steps = (StepsRun) run.entrypoint();
            PodRun reader = (PodRun) steps.get("read-dir");
            assertThat("container sees a.txt inside /input", reader.logs(), containsString("a.txt"));
            assertThat("container sees .dir-archive sentinel inside /input",
                    reader.logs(), containsString(".dir-archive"));
        }
    }

    @Test
    void dagTaskReceivesS3DirectoryArtifactAsDirectory() throws Exception {
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: s3-dir-artifact-dag-test
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
                          - name: read-dir
                            template: read-dir
                            arguments:
                              artifacts:
                                - name: dir-data
                                  s3:
                                    endpoint: '{{workflow.parameters.s3-endpoint}}'
                                    insecure: true
                                    bucket: '{{workflow.parameters.s3-bucket}}'
                                    key: data/dir.tar.gz
                                    accessKeySecret:
                                      name: minio-dir-creds
                                      key: access-key
                                    secretKeySecret:
                                      name: minio-dir-creds
                                      key: secret-key
                    - name: read-dir
                      inputs:
                        artifacts:
                          - name: dir-data
                            path: /input
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test -d /input
                          ls -a /input
                """;

        Workflow wf = patchEndpointAndBucket(yaml);
        try (WorkflowRun run = configure(ArgoWorkflowExecutor.from(wf).withKwok(kwok))
                .execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(), "workflow must succeed — /input must be a directory");
            DagRun dag = (DagRun) run.entrypoint();
            PodRun reader = (PodRun) dag.get("read-dir");
            assertThat("container sees a.txt inside /input", reader.logs(), containsString("a.txt"));
            assertThat("container sees .dir-archive sentinel inside /input",
                    reader.logs(), containsString(".dir-archive"));
        }
    }

    static Workflow patchEndpointAndBucket(String yaml) throws Exception {
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
