package eu.vnagy.argotools.junit.workflowtemplates;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.testutil.ArgoKwok;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that when two WorkflowTemplates each contain a template with the same local name
 * (e.g. both have a {@code hello-world} template), each WT's entry DAG resolves its own
 * sibling template rather than the other WT's version.
 *
 * <p>Scenario:
 * <ul>
 *   <li>{@code wftemplate-a} has a {@code hello-world} template that echoes {@code "hello from A"}
 *       and an entry DAG {@code wftemplate-a-entry} that calls it by local name.</li>
 *   <li>{@code wftemplate-b} has a {@code hello-world} template that echoes {@code "hello from B"}
 *       and an entry DAG {@code wftemplate-b-entry} that calls it by local name.</li>
 *   <li>A workflow calls both entry DAGs in parallel via {@code templateRef}.</li>
 * </ul>
 *
 * <p>The executor scopes plain-name lookups to the owning WorkflowTemplate, so
 * {@code call-a/say-hello} prints {@code "hello from A"} and
 * {@code call-b/say-hello} prints {@code "hello from B"}.
 */
class WorkflowTemplateSiblingCollisionTest {

    static ArgoKwok argoKwok;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        argoKwok.applyYaml("/wftemplate/name-collision/wt-sibling-hello-templates.yaml");
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void eachWorkflowTemplateUsesItsOwnSiblingTemplate() throws Exception {
        Path workflowPath = Path.of(getClass().getResource("/wftemplate/name-collision/wt-sibling-hello-workflow.yaml").toURI());

        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflowPath)
                .withKwok(argoKwok.container())
                .execute()) {

            assertThat(run.succeeded(), is(true));

            StepsRun runBoth = (StepsRun) run.entrypoint();
            DagRun callA = (DagRun) runBoth.get("call-a");
            DagRun callB = (DagRun) runBoth.get("call-b");

            String outputA = ((PodRun) callA.get("say-hello")).logs().strip();
            String outputB = ((PodRun) callB.get("say-hello")).logs().strip();

            assertThat(outputA, is("hello from A"));
            assertThat(outputB, is("hello from B"));
        }
    }
}
