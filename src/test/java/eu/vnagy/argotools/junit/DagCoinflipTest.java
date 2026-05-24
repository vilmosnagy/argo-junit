package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that the diamond-coinflip DAG runs to completion.
 *
 * Topology (dag-coinflip.yaml):
 *
 *   A ──────────────► B ──► D
 *   └───────────────► C ──► D
 *
 * Each node runs the {@code coinflip} steps template, which recursively re-runs
 * itself on tails until it gets heads. B and C run in parallel after A completes;
 * D waits for both B and C.
 *
 * The test only asserts overall success — the number of recursive coin flips per
 * node is non-deterministic.
 */
class DagCoinflipTest {

    @Test
    void diamondWithRecursiveCoinflip() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-coinflip.yaml").toURI()))
                .execute();

        assertThat(run.succeeded(), is(true));

        DagRun dag = (DagRun) run.entrypoint();
        for (String task : new String[]{"A", "B", "C", "D"}) {
            assertThat("task " + task, dag.get(task).succeeded(), is(true));
        }

        System.out.println(WorkflowSummary.format(run));
    }
}
