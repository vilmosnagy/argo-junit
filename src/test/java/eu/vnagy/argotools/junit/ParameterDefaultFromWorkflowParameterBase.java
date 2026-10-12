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

import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared scenarios for template input parameter defaults that reference a workflow parameter,
 * e.g. {@code default: '{{workflow.parameters.foobar}}'}: when the caller passes no argument,
 * the default is substituted with the workflow parameter's value.
 *
 * <p>Every workflow declares the workflow parameter {@code foobar} = {@code "from workflow"};
 * the consuming pod fails unless it sees the expected value.
 *
 * <p>Concrete subclasses run the workflows: {@link ParameterDefaultFromWorkflowParameterTest}
 * through argo-junit, {@link ParameterDefaultFromWorkflowParameterRealArgoTest} on a real Argo
 * controller, to check that the assertions here match how Argo behaves.
 */
public abstract class ParameterDefaultFromWorkflowParameterBase {

    /** Runs the workflow in {@code workflowYaml} to completion. */
    protected abstract WorkflowOutcome runWorkflow(String workflowYaml) throws Exception;

    @Test
    void dagTaskFallsBackToDefaultReferencingWorkflowParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-param-default-from-wf-param-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: foobar
                        value: from workflow
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: check
                            template: check
                    - name: check
                      inputs:
                        parameters:
                          - name: value
                            default: '{{workflow.parameters.foobar}}'
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.value}}" = "from workflow"
                """;

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "DAG task: parameter default must be substituted with the workflow parameter" + "\n" + outcome.summary());
    }

    @Test
    void stepFallsBackToDefaultReferencingWorkflowParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-param-default-from-wf-param-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: foobar
                        value: from workflow
                  templates:
                    - name: main
                      steps:
                        - - name: check
                            template: check
                    - name: check
                      inputs:
                        parameters:
                          - name: value
                            default: '{{workflow.parameters.foobar}}'
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.value}}" = "from workflow"
                """;

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "Step: parameter default must be substituted with the workflow parameter" + "\n" + outcome.summary());
    }

    @Test
    void dagTemplateDefaultReferencingWorkflowParameterIsPassedOn() throws Exception {
        // The default is declared on a DAG template, which hands it on to its pod via
        // {{inputs.parameters.value}}.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-template-param-default-from-wf-param-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: foobar
                        value: from workflow
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: consume
                            template: consume
                    - name: consume
                      inputs:
                        parameters:
                          - name: value
                            default: '{{workflow.parameters.foobar}}'
                      dag:
                        tasks:
                          - name: check
                            template: check
                            arguments:
                              parameters:
                                - name: value
                                  value: '{{inputs.parameters.value}}'
                    - name: check
                      inputs:
                        parameters:
                          - name: value
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.value}}" = "from workflow"
                """;

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "DAG template: parameter default must be substituted with the workflow parameter" + "\n" + outcome.summary());
    }

    @Test
    void argumentPassedByCallerOverridesDefaultReferencingWorkflowParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: param-default-from-wf-param-override-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: foobar
                        value: from workflow
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: check
                            template: check
                            arguments:
                              parameters:
                                - name: value
                                  value: from caller
                    - name: check
                      inputs:
                        parameters:
                          - name: value
                            default: '{{workflow.parameters.foobar}}'
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.value}}" = "from caller"
                """;

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "An argument passed by the caller must override the parameter default" + "\n" + outcome.summary());
    }
}
