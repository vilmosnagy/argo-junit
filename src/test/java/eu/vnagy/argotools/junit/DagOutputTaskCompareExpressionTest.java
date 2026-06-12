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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for DAG {@code outputs.parameters.valueFrom.expression} where the
 * discriminant compares a child task's output to an empty string:
 *
 * <pre>
 *   tasks['check-cache'].outputs.parameters['version'] == '' ?
 *     tasks['download-and-upload'].outputs.parameters['version'] :
 *     tasks['check-cache'].outputs.parameters['version']
 * </pre>
 *
 * <p>The test runs the workflow twice against the same MinIO and kwok instance:
 *
 * <ul>
 *   <li><b>Run 1 (cold cache):</b> the ConfigMap is absent; {@code check-cache} reads
 *       {@code ""} via kubectl, so {@code download-and-upload} runs, uploads the artifact
 *       to S3, and writes {@code version="build-v1"} into the ConfigMap via kubectl.</li>
 *   <li><b>Run 2 (warm cache):</b> {@code check-cache} reads {@code "build-v1"} from the
 *       ConfigMap, so {@code download-and-upload} is <em>omitted</em> by its {@code when}
 *       guard.  The expression condition is {@code false} → result must be
 *       {@code check-cache}'s version.  If the executor fails to resolve the expression
 *       (e.g. because the omitted task is absent from the JEXL context), the placeholder
 *       propagates into {@code consume}'s S3 key and the workflow fails.</li>
 * </ul>
 */
class DagOutputTaskCompareExpressionTest {

    private static final Logger log = LoggerFactory.getLogger(DagOutputTaskCompareExpressionTest.class);

    static final String BUCKET        = "dag-task-compare-test";
    static final String SECRET_NAME   = "minio-creds-task-compare";
    static final String FIXED_VERSION = "build-v1";

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
    void cacheHitSkipsRebuildAndProducesIdenticalArtifactContent() throws Exception {
        String expectedContent = "content-for-" + FIXED_VERSION;
        String artifactKey = "artifacts/" + FIXED_VERSION + ".txt";

        // ── Run 1: cold cache (ConfigMap absent — check-cache reads "" via kubectl) ────
        Workflow wf1 = loadWorkflow();
        try (WorkflowRun run1 = ArgoWorkflowExecutor.from(wf1).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            log.info(WorkflowSummary.format(run1));
            assertTrue(run1.succeeded(), "Run 1 (cold cache) must succeed");
        }

        try (S3Client s3 = minio.createClient()) {
            byte[] bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(artifactKey).build()
            ).asByteArray();
            assertThat("run 1 uploaded artifact content",
                    new String(bytes, StandardCharsets.UTF_8), is(expectedContent));
        }

        // ── Run 2: warm cache (ConfigMap written by run 1's download-and-upload) ───────
        Workflow wf2 = loadWorkflow();
        try (WorkflowRun run2 = ArgoWorkflowExecutor.from(wf2).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            log.info(WorkflowSummary.format(run2));
            assertTrue(run2.succeeded(),
                    "Run 2 (warm cache) must succeed — failure indicates that the DAG expression " +
                    "whose 'true' branch references the omitted 'download-and-upload' task was not " +
                    "resolved, leaving an unresolved placeholder in the 'consume' step's S3 key");
        }
    }

    /**
     * Same cache-or-build scenario as {@link #cacheHitSkipsRebuildAndProducesIdenticalArtifactContent},
     * but the {@code download-and-upload} step is itself a <em>nested DAG</em> whose output
     * parameter is forwarded via {@code valueFrom.parameter} from its child task — matching
     * the real-world pattern in the failing workflow.
     *
     * <p>On cache miss (Run 1), {@code download-and-upload-dag} runs and must expose its
     * version parameter through {@code valueFrom.parameter}.  If the executor ignores
     * {@code valueFrom.parameter}, the expression's true branch resolves to {@code null},
     * the version placeholder is left unresolved, and the {@code consume} S3 download fails.
     */
    @Test
    void nestedDagCacheHitSkipsRebuildAndProducesIdenticalArtifactContent() throws Exception {
        String expectedContent = "content-for-" + FIXED_VERSION;
        String artifactKey = "nested-dag-artifacts/" + FIXED_VERSION + ".txt";

        // ── Run 1: cold cache — download-and-upload-dag must expose its version via valueFrom.parameter ──
        Workflow wf1 = loadNestedDagWorkflow();
        try (WorkflowRun run1 = ArgoWorkflowExecutor.from(wf1).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            log.info(WorkflowSummary.format(run1));
            assertTrue(run1.succeeded(),
                    "Run 1 (cold cache, nested DAG) must succeed — failure indicates that " +
                    "valueFrom.parameter in the sub-DAG output is not resolved, leaving the " +
                    "expression's true branch null and the version placeholder unresolved");
        }

        try (S3Client s3 = minio.createClient()) {
            byte[] bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(artifactKey).build()
            ).asByteArray();
            assertThat("nested-dag run 1 uploaded artifact content",
                    new String(bytes, StandardCharsets.UTF_8), is(expectedContent));
        }

        // ── Run 2: warm cache — nested DAG omitted, expression else branch must return check-cache's version ──
        Workflow wf2 = loadNestedDagWorkflow();
        try (WorkflowRun run2 = ArgoWorkflowExecutor.from(wf2).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            log.info(WorkflowSummary.format(run2));
            assertTrue(run2.succeeded(), "Run 2 (warm cache, nested DAG) must succeed");
        }
    }

    private Workflow loadWorkflow() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(
                getClass().getResource("/dag-output-task-compare.yaml"), Workflow.class);
        setParam(wf, "s3-endpoint",   minio.endpoint());
        setParam(wf, "s3-bucket",     BUCKET);
        setParam(wf, "s3-secret-name", SECRET_NAME);
        return wf;
    }

    private Workflow loadNestedDagWorkflow() throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(
                getClass().getResource("/dag-output-nested-dag-compare.yaml"), Workflow.class);
        setParam(wf, "s3-endpoint",   minio.endpoint());
        setParam(wf, "s3-bucket",     BUCKET);
        setParam(wf, "s3-secret-name", SECRET_NAME);
        return wf;
    }

    private static void setParam(Workflow wf, String name, String value) {
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> name.equals(p.getName())).findFirst().orElseThrow()
                .setValue(value);
    }
}
