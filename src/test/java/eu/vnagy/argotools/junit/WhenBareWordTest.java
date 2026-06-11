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
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that bare-word tokens in when conditions are treated as string values, not language
 * keywords. No {{...}} substitution involved — the bare words appear literally in the when expression.
 */
class WhenBareWordTest {

    @Test
    void emptyBareWordInWhenIsOmittedWhenFalse() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: when-empty-keyword-
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: check
                            when: "empty == somethingelse"
                            template: noop
                    - name: noop
                      script:
                        image: alpine:3.23
                        command: [sh, -c]
                        source: "true"
                """).execute(Duration.ofMinutes(5))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));
            assertThat("check omitted", ((DagRun) run.entrypoint()).get("check").omitted(), is(true));
        }
    }

    @Test
    void foobarBareWordInWhenIsOmittedWhenFalse() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor.from("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: when-foobar-keyword-
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: check
                            when: "foobar == xyz"
                            template: noop
                    - name: noop
                      script:
                        image: alpine:3.23
                        command: [sh, -c]
                        source: "true"
                """).execute(Duration.ofMinutes(5))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));
            assertThat("check omitted", ((DagRun) run.entrypoint()).get("check").omitted(), is(true));
        }
    }
}
