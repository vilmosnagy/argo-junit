package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

class WorkflowSummaryTest {

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

    private static String normalizeDurations(String summary) {
        return summary.replaceAll("\\d+m \\d+s|\\d+s", "{duration}");
    }
}
