package eu.vnagy.argotools.junit;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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

        KwokContainer kwok;
        MinioContainer minio;

        @BeforeAll
        void setup() {
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
                     └─✗ consume  {duration}  artifact 'data-file': no such object: data/does-not-exist.txt
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
                     └─✗ consume  {duration}  artifact 'data-file': no such object: data/does-not-exist.txt
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
                .replaceAll("[0-9a-f]{12}", "{cid}");
    }
}
