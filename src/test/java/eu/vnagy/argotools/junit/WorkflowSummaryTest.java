package eu.vnagy.argotools.junit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.RetryOutcomeGate;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

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
                 ✔ hello-world  {duration}
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
                 ├─✔ flip-coin  {duration}
                 ├─✔ heads      {duration}
                 └─○ tails                skipped
                """;

        String tailsWon = """
                Status:  Succeeded

                STEP            DURATION  MESSAGE
                 ✔ coinflip
                 ├─✔ flip-coin  {duration}
                 ├─○ heads                skipped
                 └─✔ tails      {duration}
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
                 ├─✔ A      {duration}
                 ├─✔ B      {duration}
                 ├─✔ C      {duration}
                 └─✔ D      {duration}
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

                        STEP          DURATION  MESSAGE
                         ✔ poll-gate  {duration}  3 attempts
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

                        STEP          DURATION  MESSAGE
                         ✗ poll-gate  {duration}  exit code 1, 6 attempts
                        """));
            }
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
        return summary
                .replaceAll("\\d+m \\d+s|\\d+s", "{duration}")
                .replaceAll("\\{duration} {2,}", "{duration}  ");
    }
}
