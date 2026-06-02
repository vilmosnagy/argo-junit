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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.kwok.KwokContainer;
import eu.vnagy.argotools.junit.model.Workflow;
import eu.vnagy.argotools.junit.testutil.MinioContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies DAG-level output expressions:
 * <ul>
 *   <li>{@code outputs.parameters[].valueFrom.expression} — the DAG selects which task's
 *       output parameter to surface based on a conditional expression that can reference
 *       {@code inputs.parameters} and {@code tasks[*].outputs.parameters}.</li>
 *   <li>{@code outputs.artifacts[].fromExpression} — the DAG selects which task's output
 *       artifact to surface, using the same expression language.</li>
 * </ul>
 *
 * <p>In both cases the expression is a ternary: a condition on {@code inputs.parameters['x']}
 * chooses between two branches whose outputs come from mutually-exclusive conditional tasks.
 * The tests verify both branches of each expression to ensure the correct side is picked.
 */
class DagOutputExpressionTest {

    static final String BUCKET      = "dag-expr-test";
    static final String SECRET_NAME = "minio-creds-dag-expr";

    static KwokContainer  kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setup() {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);

        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret(SECRET_NAME, "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok  != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    /**
     * An outer DAG calls an inner DAG whose output parameter is declared with
     * {@code valueFrom.expression}.  The expression is a ternary on
     * {@code inputs.parameters['selector']}: when {@code selector == 'a'} the inner
     * DAG surfaces {@code branch-a}'s result; otherwise it surfaces {@code branch-b}'s.
     * The outer DAG then passes the chosen value to a step that writes it to S3, and
     * the test asserts the correct string landed there.
     */
    @ParameterizedTest
    @CsvSource({"a, result-from-a", "b, result-from-b"})
    void dagOutputParameterValueFromExpression(String selector, String expected) throws Exception {
        String s3Key = "param-expr-" + selector + ".txt";
        String yaml = buildParameterExpressionWorkflow(selector, s3Key);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertTrue(run.succeeded(), "Workflow must succeed before checking S3");
        }

        try (S3Client client = minio.createClient()) {
            byte[] content = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(s3Key).build())
                    .asByteArray();
            assertThat(new String(content, StandardCharsets.UTF_8).trim(), is(expected));
        }
    }

    /**
     * An outer DAG calls an inner DAG whose output artifact is declared with
     * {@code fromExpression}.  The expression is a ternary on
     * {@code inputs.parameters['mode']}: when {@code mode == 'x'} the inner DAG
     * surfaces {@code make-x}'s artifact; otherwise it surfaces {@code make-y}'s.
     * The outer DAG then passes the chosen artifact to a step that uploads it to S3,
     * and the test asserts the correct content landed there.
     */
    @ParameterizedTest
    @CsvSource({"x, artifact-content-x", "y, artifact-content-y"})
    void dagOutputArtifactFromExpression(String mode, String expected) throws Exception {
        String s3Key = "artifact-expr-" + mode + ".txt";
        String yaml = buildArtifactExpressionWorkflow(mode, s3Key);

        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertTrue(run.succeeded(), "Workflow must succeed before checking S3");
        }

        try (S3Client client = minio.createClient()) {
            byte[] content = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(BUCKET).key(s3Key).build())
                    .asByteArray();
            assertThat(new String(content, StandardCharsets.UTF_8).trim(), is(expected));
        }
    }

    // -------------------------------------------------------------------------
    // YAML builders
    // -------------------------------------------------------------------------

    /**
     * Workflow layout (parameter expression):
     *
     * <pre>
     * outer (dag)
     * ├── pick  → conditional-picker (dag, input: selector)
     * │           outputs.parameters.chosen via valueFrom.expression
     * │           ├── branch-a  (when selector == 'a') → emit-value → result "result-from-a"
     * │           └── branch-b  (when selector != 'a') → emit-value → result "result-from-b"
     * └── record → upload-value (writes {{tasks.pick.outputs.parameters.chosen}} to S3)
     * </pre>
     */
    private String buildParameterExpressionWorkflow(String selector, String s3Key) {
        return String.format("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-param-expr-%s
                spec:
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: '%s'
                      - name: s3-bucket
                        value: '%s'
                      - name: s3-secret-name
                        value: '%s'
                      - name: s3-key
                        value: '%s'
                  entrypoint: outer
                  templates:
                    - name: outer
                      dag:
                        tasks:
                          - name: pick
                            template: conditional-picker
                            arguments:
                              parameters:
                                - name: selector
                                  value: '%s'
                          - name: record
                            template: upload-value
                            dependencies: [pick]
                            arguments:
                              parameters:
                                - name: value
                                  value: '{{tasks.pick.outputs.parameters.chosen}}'

                    - name: conditional-picker
                      inputs:
                        parameters:
                          - name: selector
                      outputs:
                        parameters:
                          - name: chosen
                            valueFrom:
                              expression: "inputs.parameters['selector'] == 'a' ? tasks['branch-a'].outputs.parameters.value : tasks['branch-b'].outputs.parameters.value"
                      dag:
                        tasks:
                          - name: branch-a
                            template: emit-value
                            when: '"{{inputs.parameters.selector}}" == "a"'
                            arguments:
                              parameters:
                                - name: value
                                  value: 'result-from-a'
                          - name: branch-b
                            template: emit-value
                            when: '"{{inputs.parameters.selector}}" != "a"'
                            arguments:
                              parameters:
                                - name: value
                                  value: 'result-from-b'

                    - name: emit-value
                      inputs:
                        parameters:
                          - name: value
                      script:
                        image: alpine:3
                        command: [sh]
                        source: echo -n '{{inputs.parameters.value}}' > /tmp/out
                      outputs:
                        parameters:
                          - name: value
                            valueFrom:
                              path: /tmp/out

                    - name: upload-value
                      inputs:
                        parameters:
                          - name: value
                      script:
                        image: alpine:3
                        command: [sh]
                        source: echo -n '{{inputs.parameters.value}}' > /tmp/content.txt
                      outputs:
                        artifacts:
                          - name: result
                            path: /tmp/content.txt
                            archive:
                              none: {}
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: '{{workflow.parameters.s3-key}}'
                              accessKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: secret-key
                """,
                selector, minio.endpoint(), BUCKET, SECRET_NAME, s3Key, selector);
    }

    /**
     * Workflow layout (artifact expression):
     *
     * <pre>
     * outer (dag)
     * ├── pick    → artifact-picker (dag, input: mode)
     * │             outputs.artifacts.chosen via fromExpression
     * │             ├── make-x  (when mode == 'x') → emit-artifact → data "artifact-content-x"
     * │             └── make-y  (when mode != 'x') → emit-artifact → data "artifact-content-y"
     * └── publish → upload-artifact (artifact from: {{tasks.pick.outputs.artifacts.chosen}},
     *                                uploads to S3)
     * </pre>
     */
    private String buildArtifactExpressionWorkflow(String mode, String s3Key) {
        return String.format("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-artifact-expr-%s
                spec:
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: '%s'
                      - name: s3-bucket
                        value: '%s'
                      - name: s3-secret-name
                        value: '%s'
                      - name: s3-key
                        value: '%s'
                  entrypoint: outer
                  templates:
                    - name: outer
                      dag:
                        tasks:
                          - name: pick
                            template: artifact-picker
                            arguments:
                              parameters:
                                - name: mode
                                  value: '%s'
                          - name: publish
                            template: upload-artifact
                            dependencies: [pick]
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{tasks.pick.outputs.artifacts.chosen}}'

                    - name: artifact-picker
                      inputs:
                        parameters:
                          - name: mode
                      outputs:
                        artifacts:
                          - name: chosen
                            fromExpression: "inputs.parameters['mode'] == 'x' ? tasks['make-x'].outputs.artifacts['data'] : tasks['make-y'].outputs.artifacts['data']"
                      dag:
                        tasks:
                          - name: make-x
                            template: emit-artifact
                            when: '"{{inputs.parameters.mode}}" == "x"'
                            arguments:
                              parameters:
                                - name: content
                                  value: 'artifact-content-x'
                          - name: make-y
                            template: emit-artifact
                            when: '"{{inputs.parameters.mode}}" != "x"'
                            arguments:
                              parameters:
                                - name: content
                                  value: 'artifact-content-y'

                    - name: emit-artifact
                      inputs:
                        parameters:
                          - name: content
                      script:
                        image: alpine:3
                        command: [sh]
                        source: echo -n '{{inputs.parameters.content}}' > /tmp/data.txt
                      outputs:
                        artifacts:
                          - name: data
                            path: /tmp/data.txt
                            archive:
                              none: {}

                    - name: upload-artifact
                      inputs:
                        artifacts:
                          - name: data
                            path: /tmp/data.txt
                      script:
                        image: alpine:3
                        command: [sh]
                        source: "true"
                      outputs:
                        artifacts:
                          - name: result
                            path: /tmp/data.txt
                            archive:
                              none: {}
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: '{{workflow.parameters.s3-key}}'
                              accessKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.s3-secret-name}}'
                                key: secret-key
                """,
                mode, minio.endpoint(), BUCKET, SECRET_NAME, s3Key, mode);
    }
}
