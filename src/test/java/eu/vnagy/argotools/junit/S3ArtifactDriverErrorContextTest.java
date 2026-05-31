package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.MinioContainer;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that when an S3 download fails (e.g. key does not exist), the error message
 * surfaced in {@link WorkflowSummary} includes the bucket and key so the developer can
 * identify the problem without digging through debug logs.
 *
 * <p>Before the fix, {@code S3ArtifactDriver.download} let the raw
 * {@code NoSuchKeyException} propagate; its message is only
 * "The specified key does not exist." — no bucket or key context.
 * After the fix the driver wraps the exception with
 * "S3 download failed — bucket=… key=… artifact=…" and the summary shows that instead.
 */
class S3ArtifactDriverErrorContextTest {

    static final String BUCKET      = "s3-driver-error-test";
    static final String MISSING_KEY = "nonexistent/file.txt";
    static final String SECRET_NAME = "minio-creds-err";

    static KwokContainer  kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setup() {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);
        // Intentionally do NOT upload anything — the download must fail with NoSuchKeyException.

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
    void s3DownloadErrorIncludesBucketAndKeyInSummary() throws Exception {
        String yaml = String.format("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: s3-error-context-test
                spec:
                  entrypoint: fetch
                  templates:
                    - name: fetch
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/file.txt
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: '%s'
                              key: '%s'
                              accessKeySecret:
                                name: '%s'
                                key: access-key
                              secretKeySecret:
                                name: '%s'
                                key: secret-key
                            archive:
                              none: {}
                      script:
                        image: alpine:3
                        command: [sh]
                        source: cat /data/file.txt
                """, minio.endpoint(), BUCKET, MISSING_KEY, SECRET_NAME, SECRET_NAME);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertTrue(run.errored(), "Run must be errored when S3 key does not exist");

            String summary = WorkflowSummary.format(run);
            System.out.println(summary);
            assertThat("WorkflowSummary must include the bucket name so the error is self-explanatory",
                    summary, containsString(BUCKET));
            assertThat("WorkflowSummary must include the missing key so the error is self-explanatory",
                    summary, containsString(MISSING_KEY));
            assertThat("WorkflowSummary must include the short summary so the error is self-explanatory",
                    summary, containsString("The specified key does not exist"));
        }
    }
}
