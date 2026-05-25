package eu.vnagy.argotools.junit.executor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class DependsExpressionTest {

    // -- taskNames ------------------------------------------------------------

    @Test
    void taskNamesFromBlank() {
        assertThat(new DependsExpression(null).taskNames(),  is(Set.of()));
        assertThat(new DependsExpression("").taskNames(),    is(Set.of()));
        assertThat(new DependsExpression("   ").taskNames(), is(Set.of()));
    }

    @Test
    void taskNamesBareName() {
        assertThat(new DependsExpression("A").taskNames(), is(Set.of("A")));
    }

    @Test
    void taskNamesSimpleAnd() {
        assertThat(new DependsExpression("B && C").taskNames(), is(Set.of("B", "C")));
    }

    @Test
    void taskNamesWithQualifiers() {
        // qualifiers stripped — only A and C returned
        assertThat(new DependsExpression("A && (C.Succeeded || C.Failed)").taskNames(),
                is(Set.of("A", "C")));
    }

    @Test
    void taskNamesWithHyphens() {
        assertThat(new DependsExpression("should-execute-2.Succeeded || should-not-execute").taskNames(),
                is(Set.of("should-execute-2", "should-not-execute")));
    }

    // -- evaluate -------------------------------------------------------------

    private static WorkflowNode succeeded(String name) { return PodRun.succeeded(name); }
    private static WorkflowNode failed(String name)    { return PodRun.failed(name); }
    private static WorkflowNode errored(String name)   { return PodRun.errored(name); }
    private static WorkflowNode skipped(String name)   { return PodRun.skipped(name); }
    private static WorkflowNode omitted(String name)   { return PodRun.omitted(name); }

    @Test
    void evaluateNullOrBlankAlwaysTrue() {
        assertThat(new DependsExpression(null).evaluate(Map.of()),  is(true));
        assertThat(new DependsExpression("").evaluate(Map.of()),    is(true));
        assertThat(new DependsExpression("   ").evaluate(Map.of()), is(true));
    }

    @Test
    void evaluateBareNameMeansSucceededOrSkipped() {
        // bare name = (task.Succeeded || task.Skipped || task.Daemoned)
        assertThat(new DependsExpression("A").evaluate(Map.of("A", succeeded("A"))), is(true));
        assertThat(new DependsExpression("A").evaluate(Map.of("A", skipped("A"))),   is(true));
        assertThat(new DependsExpression("A").evaluate(Map.of("A", failed("A"))),    is(false));
        assertThat(new DependsExpression("A").evaluate(Map.of("A", errored("A"))),   is(false));
        assertThat(new DependsExpression("A").evaluate(Map.of("A", omitted("A"))),   is(false));
    }

    @Test
    void evaluateQualifiedSucceeded() {
        assertThat(new DependsExpression("A.Succeeded").evaluate(Map.of("A", succeeded("A"))), is(true));
        assertThat(new DependsExpression("A.Succeeded").evaluate(Map.of("A", failed("A"))),    is(false));
    }

    @Test
    void evaluateQualifiedFailed() {
        assertThat(new DependsExpression("A.Failed").evaluate(Map.of("A", failed("A"))),    is(true));
        assertThat(new DependsExpression("A.Failed").evaluate(Map.of("A", succeeded("A"))), is(false));
    }

    @Test
    void evaluateQualifiedSkipped() {
        assertThat(new DependsExpression("A.Skipped").evaluate(Map.of("A", skipped("A"))),   is(true));
        assertThat(new DependsExpression("A.Skipped").evaluate(Map.of("A", succeeded("A"))), is(false));
    }

    @Test
    void evaluateQualifiedErrored() {
        assertThat(new DependsExpression("A.Errored").evaluate(Map.of("A", errored("A"))),   is(true));
        assertThat(new DependsExpression("A.Errored").evaluate(Map.of("A", failed("A"))),    is(false));
        assertThat(new DependsExpression("A.Errored").evaluate(Map.of("A", succeeded("A"))), is(false));
    }

    @Test
    void evaluateQualifiedOmitted() {
        assertThat(new DependsExpression("A.Omitted").evaluate(Map.of("A", omitted("A"))),   is(true));
        assertThat(new DependsExpression("A.Omitted").evaluate(Map.of("A", skipped("A"))),   is(false));
        assertThat(new DependsExpression("A.Omitted").evaluate(Map.of("A", succeeded("A"))), is(false));
    }

    @Test
    void evaluateQualifiedDaemoned() {
        // Daemoned is never true in this implementation
        assertThat(new DependsExpression("A.Daemoned").evaluate(Map.of("A", succeeded("A"))), is(false));
    }

    @Test
    void evaluateQualifiedAnySucceeded() {
        // AnySucceeded is equivalent to Succeeded for non-withItems tasks
        assertThat(new DependsExpression("A.AnySucceeded").evaluate(Map.of("A", succeeded("A"))), is(true));
        assertThat(new DependsExpression("A.AnySucceeded").evaluate(Map.of("A", failed("A"))),    is(false));
    }

    @Test
    void evaluateQualifiedAllFailed() {
        // AllFailed is equivalent to Failed for non-withItems tasks
        assertThat(new DependsExpression("A.AllFailed").evaluate(Map.of("A", failed("A"))),    is(true));
        assertThat(new DependsExpression("A.AllFailed").evaluate(Map.of("A", succeeded("A"))), is(false));
    }

    @Test
    void evaluateAndOperator() {
        Map<String, WorkflowNode> both = Map.of("B", succeeded("B"), "C", succeeded("C"));
        Map<String, WorkflowNode> oneF = Map.of("B", succeeded("B"), "C", failed("C"));
        assertThat(new DependsExpression("B && C").evaluate(both), is(true));
        assertThat(new DependsExpression("B && C").evaluate(oneF), is(false));
    }

    @Test
    void evaluateOrOperator() {
        Map<String, WorkflowNode> bOk = Map.of("B", succeeded("B"), "C", failed("C"));
        Map<String, WorkflowNode> none = Map.of("B", failed("B"), "C", failed("C"));
        assertThat(new DependsExpression("B || C").evaluate(bOk),  is(true));
        assertThat(new DependsExpression("B || C").evaluate(none), is(false));
    }

    @Test
    void evaluateParenthesisedExpression() {
        String expr = "A && (C.Succeeded || C.Failed)";

        // A=ok, C=failed → true && (false || true) = true
        assertThat(new DependsExpression(expr).evaluate(
                Map.of("A", succeeded("A"), "C", failed("C"))), is(true));

        // A=ok, C=ok → true && (true || false) = true
        assertThat(new DependsExpression(expr).evaluate(
                Map.of("A", succeeded("A"), "C", succeeded("C"))), is(true));

        // A=failed → false && anything = false
        assertThat(new DependsExpression(expr).evaluate(
                Map.of("A", failed("A"), "C", failed("C"))), is(false));
    }

    @Test
    void evaluateSkippedNodeSatisfiesBareDependent() {
        // bare name includes Skipped, so a when-skipped node satisfies downstream bare deps
        assertThat(new DependsExpression("should-execute-2.Succeeded || should-not-execute").evaluate(
                Map.of("should-execute-2", succeeded("should-execute-2"),
                       "should-not-execute", skipped("should-not-execute"))), is(true));
    }

    @Test
    void evaluateOmittedNodeDoesNotSatisfyBareDependent() {
        // Omitted (depends-false) does NOT satisfy a bare dep — only Succeeded/Skipped/Daemoned do
        assertThat(new DependsExpression("should-execute-2.Succeeded || should-not-execute").evaluate(
                Map.of("should-execute-2", failed("should-execute-2"),
                       "should-not-execute", omitted("should-not-execute"))), is(false));
    }

    @Test
    void evaluateThatTaskCanBeNamedSucceededOrSkipped() {
        DependsExpression dependsExpression = new DependsExpression("Succeeded.Succeeded || Skipped.Skipped");
        assertThat(dependsExpression.evaluate(
                        buildDependencyTree(succeeded("Succeeded"), succeeded("Skipped"))
                ),
                is(true)
        );
        assertThat(dependsExpression.evaluate(
                        buildDependencyTree(skipped("Succeeded"), skipped("Skipped"))
                ),
                is(true)
        );
        assertThat(dependsExpression.evaluate(
                        buildDependencyTree(skipped("Succeeded"), succeeded("Skipped"))
                ),
                is(false)
        );
        assertThat(dependsExpression.taskNames(), is(Set.of("Succeeded", "Skipped")));
    }

    private Map<String, WorkflowNode> buildDependencyTree(WorkflowNode... nodes) {
        return Arrays
                .stream(nodes)
                .map(n -> Map.entry(n.name(), n))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
