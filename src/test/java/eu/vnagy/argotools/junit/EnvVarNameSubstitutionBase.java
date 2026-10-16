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
 * Shared scenarios for template tags in env var <em>names</em>, e.g.
 * {@code env[].name: '{{inputs.parameters.var-name}}'}: Argo substitutes the whole template, so
 * the name is resolved just like the value, for plain {@code value:} entries as well as
 * {@code valueFrom.secretKeyRef} / {@code valueFrom.configMapKeyRef} entries.
 *
 * <p>Every consuming pod fails unless the expected value arrives under the substituted name.
 * Subclasses must provide, before the tests run, the secret {@value #SECRET_NAME} with
 * {@value #SECRET_KEY} = {@value #SECRET_VALUE} and the config map {@value #CONFIG_MAP_NAME} with
 * {@value #CONFIG_MAP_KEY} = {@value #CONFIG_MAP_VALUE}, in the namespace the workflows run in.
 *
 * <p>Concrete subclasses run the workflows: {@link EnvVarNameSubstitutionTest} through
 * argo-junit, {@link EnvVarNameSubstitutionRealArgoTest} on a real Argo controller, to check
 * that the assertions here match how Argo behaves.
 *
 * @see <a href="https://github.com/vilmosnagy/argo-junit/issues/175">#175</a>
 */
public abstract class EnvVarNameSubstitutionBase {

    static final String SECRET_NAME = "env-name-secret";
    static final String SECRET_KEY = "token";
    static final String SECRET_VALUE = "s3cr3t";
    static final String CONFIG_MAP_NAME = "env-name-config";
    static final String CONFIG_MAP_KEY = "greeting";
    static final String CONFIG_MAP_VALUE = "hello from configmap";

    /** Runs the workflow in {@code workflowYaml} to completion. */
    protected abstract WorkflowOutcome runWorkflow(String workflowYaml) throws Exception;

    @Test
    void plainEnvVarNameFromInputParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: env-name-input-param-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: run
                            template: check
                            arguments:
                              parameters:
                                - name: var-name
                                  value: GREETING
                                - name: var-value
                                  value: hello
                    - name: check
                      inputs:
                        parameters:
                          - name: var-name
                          - name: var-value
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - name: '{{inputs.parameters.var-name}}'
                            value: '{{inputs.parameters.var-value}}'
                        source: |
                          test "${GREETING:-<unset>}" = "hello"
                """;

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "Plain env var name must be substituted with the input parameter" + "\n" + outcome.summary());
    }

    @Test
    void plainEnvVarNameFromWorkflowParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: env-name-wf-param-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: var-name
                        value: GREETING
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: run
                            template: check
                    - name: check
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - name: '{{workflow.parameters.var-name}}'
                            value: hello
                        source: |
                          test "${GREETING:-<unset>}" = "hello"
                """;

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "Plain env var name must be substituted with the workflow parameter" + "\n" + outcome.summary());
    }

    @Test
    void secretKeyRefEnvVarNameFromInputParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: env-name-secret-ref-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: run
                            template: check
                            arguments:
                              parameters:
                                - name: var-name
                                  value: TOKEN
                    - name: check
                      inputs:
                        parameters:
                          - name: var-name
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - name: '{{inputs.parameters.var-name}}'
                            valueFrom:
                              secretKeyRef:
                                name: %s
                                key: %s
                        source: |
                          test "${TOKEN:-<unset>}" = "%s"
                """.formatted(SECRET_NAME, SECRET_KEY, SECRET_VALUE);

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "secretKeyRef env var name must be substituted with the input parameter" + "\n" + outcome.summary());
    }

    @Test
    void configMapKeyRefEnvVarNameFromInputParameter() throws Exception {
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: env-name-config-map-ref-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: run
                            template: check
                            arguments:
                              parameters:
                                - name: var-name
                                  value: GREETING
                    - name: check
                      inputs:
                        parameters:
                          - name: var-name
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - name: '{{inputs.parameters.var-name}}'
                            valueFrom:
                              configMapKeyRef:
                                name: %s
                                key: %s
                        source: |
                          test "${GREETING:-<unset>}" = "%s"
                """.formatted(CONFIG_MAP_NAME, CONFIG_MAP_KEY, CONFIG_MAP_VALUE);

        WorkflowOutcome outcome = runWorkflow(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "configMapKeyRef env var name must be substituted with the input parameter" + "\n" + outcome.summary());
    }
}
