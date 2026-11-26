package eu.vnagy.argotools.junit.expressions;

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
import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Runs the expression tag scenarios through argo-junit. Also checks what only argo-junit can
 * report: an expr-lang feature argo-junit does not implement fails the node with a message
 * naming it, instead of passing the tag through as text.
 *
 * @see ExpressionTagBase
 */
class ExpressionTagTest extends ExpressionTagBase {

    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from(workflowYaml).execute(Duration.ofMinutes(10))) {
            return new WorkflowOutcome(run.succeeded(), WorkflowSummary.format(run));
        }
    }

    @Test
    void unsupportedSprigFunctionFailsWithClearMessage() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                        apiVersion: argoproj.io/v1alpha1
                        kind: Workflow
                        metadata:
                          name: expr-tag-unsupported-test
                        spec:
                          entrypoint: main
                          templates:
                            - name: main
                              script:
                                image: alpine:3
                                command: [sh, -e]
                                env:
                                  - {name: VERSION, value: "{{=sprig.semverCompare('>=1.2', '1.3.0')}}"}
                                source: |
                                  true
                        """)
                .execute(Duration.ofMinutes(10))) {
            String summary = WorkflowSummary.format(run);
            assertThat(summary, run.succeeded(), is(false));
            assertThat(summary, containsString("sprig.semverCompare"));
            assertThat(summary, containsString("not supported by argo-junit"));
        }
    }
}
