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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.MinioContainer;
import eu.vnagy.argotools.junit.testutil.RetryOutcomeGate;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import eu.vnagy.argotools.junit.testutil.LoggerExtension;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class WorkflowSummaryTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void helloWorld() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/hello-world.yaml").toURI()))
                .execute();

        assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                Status:  Succeeded

                STEP            DURATION  MESSAGE
                 ✔ hello-world  {duration}  {cid}
                """));
    }

    @Test
    void coinflip() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/coinflip.yaml").toURI()))
                .execute();

        String summary = normalizeDurations(WorkflowSummary.format(run));

        String headsWon = """
                Status:  Succeeded

                STEP            DURATION  MESSAGE
                 ✔ coinflip
                 ├─✔ flip-coin  {duration}  {cid}
                 ├─✔ heads      {duration}  {cid}
                 └─○ tails                skipped
                """;

        String tailsWon = """
                Status:  Succeeded

                STEP            DURATION  MESSAGE
                 ✔ coinflip
                 ├─✔ flip-coin  {duration}  {cid}
                 ├─○ heads                skipped
                 └─✔ tails      {duration}  {cid}
                """;

        assertThat(summary, anyOf(equalTo(headsWon), equalTo(tailsWon)));
    }

    @Test
    void dagDiamond() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-diamond.yaml").toURI()))
                .execute();

        assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                Status:  Succeeded

                STEP        DURATION  MESSAGE
                 ✔ diamond
                 ├─✔ A      {duration}  {cid}
                 ├─✔ B      {duration}  {cid}
                 ├─✔ C      {duration}  {cid}
                 └─✔ D      {duration}  {cid}
                """));
    }

    @Test
    void retrySucceededShowsAttemptCount() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            Workflow wf = YAML.readValue(getClass().getResource("/retry-gate.yaml"), Workflow.class);
            setParam(wf, "port", String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("workflow succeeded", run.succeeded(), is(true));
                assertThat("3 attempts recorded", ((PodRun) run.entrypoint()).attempts(), is(3));
                assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                        Status:  Succeeded

                        STEP            DURATION  MESSAGE
                         ✔ poll-gate              3 attempts
                         ├─attempt 1 ✗  {duration}  exit code 1  {cid}
                         ├─attempt 2 ✗  {duration}  exit code 1  {cid}
                         └─attempt 3 ✔  {duration}  {cid}
                        """));
            }
        }
    }

    @Test
    void retryExhaustedShowsAttemptCountAndExitCode() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            for (int i = 0; i < 6; i++) gate.willFail();

            Workflow wf = YAML.readValue(getClass().getResource("/retry-gate.yaml"), Workflow.class);
            setParam(wf, "port", String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("workflow failed after exhausting retries", run.failed(), is(true));
                assertThat("6 attempts (1 initial + limit 5)", ((PodRun) run.entrypoint()).attempts(), is(6));
                assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                        Status:  Failed

                        STEP            DURATION  MESSAGE
                         ✗ poll-gate              6 attempts
                         ├─attempt 1 ✗  {duration}  exit code 1  {cid}
                         ├─attempt 2 ✗  {duration}  exit code 1  {cid}
                         ├─attempt 3 ✗  {duration}  exit code 1  {cid}
                         ├─attempt 4 ✗  {duration}  exit code 1  {cid}
                         ├─attempt 5 ✗  {duration}  exit code 1  {cid}
                         └─attempt 6 ✗  {duration}  exit code 1  {cid}
                        """));
            }
        }
    }

    @Test
    void retriedDagSucceededShowsAttemptSubtrees() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            Workflow wf = YAML.readValue(getClass().getResource("/retry-dag.yaml"), Workflow.class);
            setParam(wf, "port", String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("dag succeeded", run.succeeded(), is(true));
                assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                        Status:  Succeeded

                        STEP            DURATION  MESSAGE
                         ✔ my-dag                 3 attempts
                         ├─attempt 1 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 2 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         └─attempt 3 ✔
                            └─✔ gate    {duration}  {cid}
                        """));
            }
        }
    }

    @Test
    void retriedDagExhaustedShowsAllFailedAttempts() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            for (int i = 0; i < 6; i++) gate.willFail();

            Workflow wf = YAML.readValue(getClass().getResource("/retry-dag.yaml"), Workflow.class);
            setParam(wf, "port", String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("dag failed after exhausting retries", run.failed(), is(true));
                assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                        Status:  Failed

                        STEP            DURATION  MESSAGE
                         ✗ my-dag                 6 attempts
                         ├─attempt 1 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 2 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 3 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 4 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 5 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         └─attempt 6 ✗
                            └─✗ gate    {duration}  exit code 1  {cid}
                        """));
            }
        }
    }

    @Test
    void retriedStepsSucceededShowsAttemptSubtrees() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            gate.willFail();
            gate.willFail();
            gate.willSucceed();

            Workflow wf = YAML.readValue(getClass().getResource("/retry-steps.yaml"), Workflow.class);
            setParam(wf, "port", String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("steps succeeded", run.succeeded(), is(true));
                assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                        Status:  Succeeded

                        STEP            DURATION  MESSAGE
                         ✔ my-steps               3 attempts
                         ├─attempt 1 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 2 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         └─attempt 3 ✔
                            └─✔ gate    {duration}  {cid}
                        """));
            }
        }
    }

    @Test
    void retriedStepsExhaustedShowsAllFailedAttempts() throws Exception {
        try (var gate = new RetryOutcomeGate()) {
            for (int i = 0; i < 6; i++) gate.willFail();

            Workflow wf = YAML.readValue(getClass().getResource("/retry-steps.yaml"), Workflow.class);
            setParam(wf, "port", String.valueOf(gate.port()));

            try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute()) {
                assertThat("steps failed after exhausting retries", run.failed(), is(true));
                assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                        Status:  Failed

                        STEP            DURATION  MESSAGE
                         ✗ my-steps               6 attempts
                         ├─attempt 1 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 2 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 3 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 4 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         ├─attempt 5 ✗
                         │  └─✗ gate    {duration}  exit code 1  {cid}
                         └─attempt 6 ✗
                            └─✗ gate    {duration}  exit code 1  {cid}
                        """));
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ErroredArtifact {

        static final String BUCKET = "summary-errored-artifact-test";

        @RegisterExtension
        LoggerExtension loggerExtension = new LoggerExtension();

        KwokContainer kwok;
        MinioContainer minio;

        @BeforeAll
        void setup() throws IOException {
            minio = new MinioContainer();
            minio.start();
            minio.createBucket(BUCKET);

            kwok = new KwokContainer();
            kwok.start();
            kwok.createClient()
                    .secrets()
                    .inNamespace("default")
                    .resource(minio.credentialsSecret("minio-creds", "access-key", "secret-key"))
                    .create();
            kwok.createClient()
                    .secrets()
                    .inNamespace("default")
                    .resource(new SecretBuilder()
                            .withNewMetadata().withName("bad-creds").endMetadata()
                            .withData(Map.of(
                                    "access-key", Base64.getEncoder().encodeToString("wrong-access-key".getBytes()),
                                    "secret-key", Base64.getEncoder().encodeToString("wrong-secret-key".getBytes())))
                            .build())
                    .create();

            // Upload a truncated tar.gz: toByteArray() is called after tar.finish() but before
            // gzip.close(), so the 8-byte gzip trailer (CRC32 + ISIZE) has not been written yet.
            // extractTarGz reads all compressed data successfully, then hits EOF when verifying
            // the trailer and throws EOFException with a null message.
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(buf);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
                byte[] content = "hello".getBytes();
                TarArchiveEntry entry = new TarArchiveEntry("file.txt");
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
                tar.finish();
                byte[] truncated = buf.toByteArray(); // gzip trailer not written yet
                try (var s3 = minio.createClient()) {
                    s3.putObject(PutObjectRequest.builder()
                            .bucket(BUCKET)
                            .key("data/corrupt.tar.gz")
                            .build(), RequestBody.fromBytes(truncated));
                }
            }
        }

        @AfterAll
        void tearDown() {
            if (kwok != null) kwok.stop();
            if (minio != null) minio.stop();
        }

        @Test
        void dagTaskErroredShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: dag-errored-summary-test
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/does-not-exist.txt'): S3 download failed — bucket=summary-errored-artifact-test key=data/does-not-exist.txt artifact=data-file: The specified key does not exist. {s3-sdk}
                    """));
        }

        @Test
        void stepsStepErroredShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: steps-errored-summary-test
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/does-not-exist.txt'): S3 download failed — bucket=summary-errored-artifact-test key=data/does-not-exist.txt artifact=data-file: The specified key does not exist. {s3-sdk}
                    """));
        }

        @Test
        void dagTaskSecretKeyNotFoundShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: dag-secret-key-not-found-summary-test
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
                                          key: wrong-key
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/hello.txt'): Key 'wrong-key' not found in Secret 'minio-creds'
                    """));
        }

        @Test
        void stepsStepSecretKeyNotFoundShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: steps-secret-key-not-found-summary-test
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
                                          key: wrong-key
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/hello.txt'): Key 'wrong-key' not found in Secret 'minio-creds'
                    """));
        }

        @Test
        void dagTaskWrongCredentialsShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: dag-wrong-creds-summary-test
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
                                          name: bad-creds
                                          key: access-key
                                        secretKeySecret:
                                          name: bad-creds
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/hello.txt'): S3 download failed — bucket=summary-errored-artifact-test key=data/hello.txt artifact=data-file: The Access Key Id you provided does not exist in our records. {s3-sdk}
                    """));
        }

        @Test
        void stepsStepWrongCredentialsShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: steps-wrong-creds-summary-test
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
                                          name: bad-creds
                                          key: access-key
                                        secretKeySecret:
                                          name: bad-creds
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/hello.txt'): S3 download failed — bucket=summary-errored-artifact-test key=data/hello.txt artifact=data-file: The Access Key Id you provided does not exist in our records. {s3-sdk}
                    """));
        }

        @Test
        void dagTaskSecretNotFoundShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: dag-secret-not-found-summary-test
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
                                          name: does-not-exist-secret
                                          key: access-key
                                        secretKeySecret:
                                          name: does-not-exist-secret
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/hello.txt'): Secret 'does-not-exist-secret' not found in namespace 'default'
                    """));
        }

        @Test
        void stepsStepSecretNotFoundShowsMessageInSummary() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: steps-secret-not-found-summary-test
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
                                          name: does-not-exist-secret
                                          key: access-key
                                        secretKeySecret:
                                          name: does-not-exist-secret
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
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/hello.txt'): Secret 'does-not-exist-secret' not found in namespace 'default'
                    """));
        }

        @Test
        void dagTaskEofExceptionShowsClassNameWhenMessageIsNull() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: dag-eof-null-message-test
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
                                        key: data/corrupt.tar.gz
                                        accessKeySecret:
                                          name: minio-creds
                                          key: access-key
                                        secretKeySecret:
                                          name: minio-creds
                                          key: secret-key
                        - name: consume
                          inputs:
                            artifacts:
                              - name: data-file
                                path: /data/input.txt
                          script:
                            image: alpine:3
                            command: [sh, -e]
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/corrupt.tar.gz'): java.io.EOFException
                    """));
        }

        @Test
        void dagTaskEofExceptionLogsStackTrace() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: dag-eof-log-stacktrace-test
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
                                        key: data/corrupt.tar.gz
                                        accessKeySecret:
                                          name: minio-creds
                                          key: access-key
                                        secretKeySecret:
                                          name: minio-creds
                                          key: secret-key
                        - name: consume
                          inputs:
                            artifacts:
                              - name: data-file
                                path: /data/input.txt
                          script:
                            image: alpine:3
                            command: [sh, -e]
                            source: cat /data/input.txt
                    """;

            ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            boolean warnHasStackTrace = loggerExtension.events().stream()
                    .filter(e -> e.getFormattedMessage().contains("data/corrupt.tar.gz"))
                    .anyMatch(e -> e.getThrowableProxy() != null);
            assertThat("WARN log for artifact download failure includes stack trace", warnHasStackTrace, is(true));
        }

        @Test
        void stepsStepEofExceptionShowsClassNameWhenMessageIsNull() throws Exception {
            String yaml = """
                    apiVersion: argoproj.io/v1alpha1
                    kind: Workflow
                    metadata:
                      name: steps-eof-null-message-test
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
                                        key: data/corrupt.tar.gz
                                        accessKeySecret:
                                          name: minio-creds
                                          key: access-key
                                        secretKeySecret:
                                          name: minio-creds
                                          key: secret-key
                        - name: consume
                          inputs:
                            artifacts:
                              - name: data-file
                                path: /data/input.txt
                          script:
                            image: alpine:3
                            command: [sh, -e]
                            source: cat /data/input.txt
                    """;

            WorkflowRun run = ArgoWorkflowExecutor.from(patchEndpointAndBucket(yaml)).withKwok(kwok).execute();

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Errored

                    STEP          DURATION  MESSAGE
                     ✗ main                 error
                     └─✗ consume  {duration}  artifact 'data-file' (key='data/corrupt.tar.gz'): java.io.EOFException
                    """));
        }

        private Workflow patchEndpointAndBucket(String yaml) throws Exception {
            Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "s3-endpoint".equals(p.getName())).findFirst().orElseThrow()
                    .setValue(minio.endpoint());
            wf.getSpec().getArguments().getParameters().stream()
                    .filter(p -> "s3-bucket".equals(p.getName())).findFirst().orElseThrow()
                    .setValue(BUCKET);
            return wf;
        }
    }

    private static void setParam(Workflow wf, String name, String value) {
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> name.equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(value);
    }

    private static String normalizeDurations(String summary) {
        // Replace duration values, then collapse any padding spaces left over between the
        // duration placeholder and a non-empty MESSAGE column to a canonical two spaces.
        // Also replace 12-char lowercase hex container short IDs with {cid}.
        return summary
                .replaceAll("\\d+m \\d+s|\\d+s", "{duration}")
                .replaceAll("\\{duration} {2,}", "{duration}  ")
                .replaceAll("[0-9a-f]{12}", "{cid}")
                .replaceAll("\\(Service: S3, Status Code: \\d+[^)]*\\)", "{s3-sdk}");
    }
}
