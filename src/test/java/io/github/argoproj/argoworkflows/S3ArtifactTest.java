package io.github.argoproj.argoworkflows;

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
import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.S3Artifact;
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class S3ArtifactTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final String BUCKET = "test-artifacts";

    static KwokContainer kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setup() {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);

        kwok = new KwokContainer();
        kwok.start();

        // Secret name and key names match the upstream example YAMLs
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret("my-s3-credentials", "accessKey", "secretKey"))
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    @Test
    void outputArtifactUploadedToS3() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/output-artifact-s3.yaml"), Workflow.class);
        redirectS3(outputArtifact(wf, "message"), "output-test/message.tgz");

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(run.succeeded(), is(true));
        }
        try (S3Client client = minio.createClient()) {
            long size = client.headObject(HeadObjectRequest.builder()
                    .bucket(BUCKET)
                    .key("output-test/message.tgz")
                    .build()).contentLength();
            assertThat(size, greaterThan(0L));
        }
    }

    @Test
    void inputArtifactDownloadedFromS3() throws Exception {
        try (S3Client client = minio.createClient()) {
            client.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET)
                    .key("input-test/my-art.tgz")
                    .build(), RequestBody.fromBytes(singleFileTarGz("my-artifact", "hello from minio\n")));
        }
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/input-artifact-s3.yaml"), Workflow.class);
        // Override the image to avoid pulling the large debian image
        wf.getSpec().getTemplates().get(0).getContainer().setImage("busybox");
        redirectS3(inputArtifact(wf, "my-art"), "input-test/my-art.tgz");

        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("my-artifact"));
            assertThat(run.succeeded(), is(true));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Artifact outputArtifact(Workflow wf, String name) {
        return artifact(wf.getSpec().getTemplates().get(0).getOutputs().getArtifacts(), name);
    }

    private static Artifact inputArtifact(Workflow wf, String name) {
        return artifact(wf.getSpec().getTemplates().get(0).getInputs().getArtifacts(), name);
    }

    private static Artifact artifact(List<Artifact> artifacts, String name) {
        return artifacts.stream()
                .filter(a -> name.equals(a.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Artifact '" + name + "' not found"));
    }

    /** Mutates only the connection params; credential refs in the YAML are kept as-is. */
    private void redirectS3(Artifact artifact, String key) {
        S3Artifact s3 = artifact.getS3();
        s3.setEndpoint(minio.endpoint());
        s3.setBucket(BUCKET);
        s3.setKey(key);
        s3.setInsecure(true);
    }

    /** Returns a tar.gz archive containing a single file with the given name and content. */
    private static byte[] singleFileTarGz(String entryName, String content) throws IOException {
        byte[] bytes = content.getBytes();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(bytes.length);
            tar.putArchiveEntry(entry);
            tar.write(bytes);
            tar.closeArchiveEntry();
        }
        return baos.toByteArray();
    }
}
