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
import eu.vnagy.argotools.junit.executor.ItemRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Year;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * Runs the upstream examples that use expression tag templates ({@code {{=...}}}). The expected
 * values are the ones the Argo controller produces for the same examples.
 *
 * @see <a href="https://github.com/vilmosnagy/argo-junit/issues/168">#168</a>
 */
class ExpressionTagTemplateTest {

    @Test
    void expressionTagTemplateWorkflow() throws Exception {
        try (WorkflowRun run = execute("expression-tag-template-workflow.yaml")) {
            assertThat(WorkflowSummary.format(run), run.succeeded(), is(true));

            // withParam: "{{=toJson(filter([1, 3], {# > 1}))}}" expands to a single item, 3
            ItemRun loop = (ItemRun) ((DagRun) run.entrypoint()).get("task-0");
            assertThat(loop.children(), hasSize(1));
            PodRun pod = (PodRun) loop.children().getFirst();
            assertThat(pod.collectedOutputParams().get("output"),
                    is("hello 30 @ " + Year.now() + "\n"));
        }
    }

    @Test
    void expressionDestructureJson() throws Exception {
        try (WorkflowRun run = execute("expression-destructure-json.yaml")) {
            assertThat(WorkflowSummary.format(run), run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs().trim(), is("123"));
        }
    }

    @Test
    void expressionDestructureJsonComplex() throws Exception {
        try (WorkflowRun run = execute("expression-destructure-json-complex.yaml")) {
            assertThat(WorkflowSummary.format(run), run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs().trim(),
                    is("[43][43][{\"age\":43,\"name\":\"Baris\"}][\"Baris\",\"Mo\",\"Jai\"]"));
        }
    }

    @Test
    void expressionReusingVerboseSnippets() throws Exception {
        try (WorkflowRun run = execute("expression-reusing-verbose-snippets.yaml")) {
            assertThat(WorkflowSummary.format(run), run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs().trim(),
                    is("The week's average temperature was 35.8 with a minimum of 15 and a maximum of 57."));
        }
    }

    @Test
    void conditionalsRunsEveryWhenForm() throws Exception {
        // should-print defaults to "true", and workflow.parameters.json is the JSON list of the
        // workflow parameters, so all three when forms evaluate true.
        try (WorkflowRun run = execute("conditionals.yaml")) {
            assertThat(WorkflowSummary.format(run), run.succeeded(), is(true));
            StepsRun steps = (StepsRun) run.entrypoint();
            assertThat(steps.steps().stream().map(n -> n.name() + ":" + n.succeeded()).sorted().toList(),
                    contains("print-hello-expr-json:true", "print-hello-expr:true", "print-hello-govaluate:true"));
        }
    }

    private WorkflowRun execute(String example) throws Exception {
        return ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/" + example).toURI()))
                .execute(Duration.ofMinutes(10));
    }
}
