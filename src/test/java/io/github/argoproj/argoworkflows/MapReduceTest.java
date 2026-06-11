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

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowNode;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.DAGTask;
import eu.vnagy.argotools.junit.model.IoK8sApiCoreV1SecretKeySelector;
import eu.vnagy.argotools.junit.model.S3Artifact;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.MinioContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class MapReduceTest {

    static final String BUCKET = "map-reduce-test";
    static final String SECRET = "minio-creds";

    static KwokContainer  kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setUp() throws Exception {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);

        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret(SECRET, "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok  != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    @Test
    void mapReduceProducesCorrectTotal() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper()
                .readValue(getClass().getResource("/examples/map-reduce.yaml"), Workflow.class);
        patchAllS3(wf);

        try (var run = ArgoWorkflowExecutor.from(wf).withKwok(kwok)
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            // split writes part files to S3 and dumps the part-ID array to stdout
            assertThat("split succeeded", dag.get("split").succeeded(), is(true));
            assertThat("split stdout is the JSON part-ID array",
                    ((PodRun) dag.get("split")).logs().trim(), is("[\"0\", \"1\", \"2\", \"3\"]"));

            // map fans out once per part ID; each iteration reads its part and writes bar = foo*2
            assertThat("map has 4 iterations", dag.get("map").children().size(), is(4));
            assertThat("all map iterations succeeded",
                    dag.get("map").children().stream().allMatch(WorkflowNode::succeeded), is(true));

            assertThat("reduce succeeded", dag.get("reduce").succeeded(), is(true));

            // reduce computes sum(bar) = (1+2+3+4)*2 = 20 and writes it to S3 as total.json.
            // The key is {{workflow.name}}/total.json; find it by listing the bucket.
            assertThat("reduce total is 20", readTotalJson().trim(), is("{\"total\": 20}"));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void patchAllS3(Workflow wf) {
        for (Template template : wf.getSpec().getTemplates()) {
            if (template.getInputs() != null && template.getInputs().getArtifacts() != null)
                template.getInputs().getArtifacts().forEach(this::patchS3);
            if (template.getOutputs() != null && template.getOutputs().getArtifacts() != null)
                template.getOutputs().getArtifacts().forEach(this::patchS3);
            if (template.getDag() != null)
                for (DAGTask task : template.getDag().getTasks())
                    if (task.getArguments() != null && task.getArguments().getArtifacts() != null)
                        task.getArguments().getArtifacts().forEach(this::patchS3);
        }
    }

    private void patchS3(Artifact artifact) {
        if (artifact.getS3() == null) return;
        S3Artifact s3 = artifact.getS3();
        s3.setEndpoint(minio.endpoint());
        s3.setBucket(BUCKET);
        s3.setInsecure(true);
        s3.setAccessKeySecret(secretRef("access-key"));
        s3.setSecretKeySecret(secretRef("secret-key"));
    }

    /** Finds the workflow's total.json in S3 (key = {workflow.name}/total.json) and returns its content. */
    private String readTotalJson() {
        try (S3Client client = minio.createClient()) {
            var list = client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(BUCKET).build());
            String key = list.contents().stream()
                    .map(o -> o.key())
                    .filter(k -> k.endsWith("/total.json"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("total.json not found in bucket " + BUCKET));
            return new String(client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(key).build()).asByteArray());
        }
    }

    private static IoK8sApiCoreV1SecretKeySelector secretRef(String key) {
        var ref = new IoK8sApiCoreV1SecretKeySelector();
        ref.setName(SECRET);
        ref.setKey(key);
        return ref;
    }
}
