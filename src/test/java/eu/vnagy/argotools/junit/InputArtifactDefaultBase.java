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

import eu.vnagy.argotools.junit.testutil.SiloContainer;
import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared scenarios for Argo's default semantics of input artifacts that declare a location
 * ({@code inputs.artifacts[].s3}): the declared location is only a fallback, used when the
 * caller passes no argument for that artifact; an argument passed by the caller wins.
 *
 * <p>Covers composite ({@code dag} / {@code steps}) templates as well as pod templates, and
 * that a nested template's {@code {{inputs.artifacts.*}}} is scoped to that template — it must
 * not pick up an artifact of the same name that was passed to an enclosing template.
 *
 * <p>Every consuming pod asserts on the content it sees: {@code "from default"} is the S3
 * object behind the declared default, {@code "from caller"} is what the {@code produce} task
 * writes and passes explicitly.
 *
 * <p>Concrete subclasses run the workflows: {@link InputArtifactDefaultTest} through argo-junit,
 * {@link InputArtifactDefaultRealArgoTest} on a real Argo controller, to check that the
 * assertions here match how Argo behaves. The S3 endpoint in each workflow is a {@code '%s'}
 * placeholder filled with {@link #s3Endpoint()}, since Silo is reached differently from each.
 *
 * @see <a href="https://github.com/vilmosnagy/argo-junit/issues/167">#167</a>
 */
public abstract class InputArtifactDefaultBase {

    static final String BUCKET = "input-artifact-default-test";
    static final String SECRET_NAME = "minio-creds";
    static final String SILO_ALIAS = "silo";

    static Network network;
    static SiloContainer minio;

    @BeforeAll
    static void setUpSilo() {
        network = Network.newNetwork();
        minio = new SiloContainer().withNetwork(network).withNetworkAliases(SILO_ALIAS);
        minio.start();
        minio.createBucket(BUCKET);

        try (S3Client client = minio.createClient()) {
            client.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key("default/data.txt").build(),
                    RequestBody.fromString("from default"));
        }
    }

    @AfterAll
    static void tearDownSilo() {
        if (minio != null) minio.stop();
        if (network != null) network.close();
    }

    /** Returns Silo's {@code host:port} as seen by the workflow's pods. */
    protected abstract String s3Endpoint();

    /** Runs the workflow in {@code workflowYaml} to completion. */
    protected abstract WorkflowOutcome runWorkflow(String workflowYaml) throws Exception;

    @Test
    void dagTemplateInputArtifactDefaultIsUsedWhenNoArgumentPassed() throws Exception {
        // `consume` is a DAG template whose input artifact declares an S3 default; the caller
        // passes no artifact, so `{{inputs.artifacts.data}}` must resolve to the S3 object.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-input-artifact-default-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: consume
                            template: consume
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      dag:
                        tasks:
                          - name: check
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{inputs.artifacts.data}}'
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from default" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "DAG template: declared S3 default of an input artifact must be used when the caller passes none" + "\n" + outcome.summary());
    }

    @Test
    void stepsTemplateInputArtifactDefaultIsUsedWhenNoArgumentPassed() throws Exception {
        // Same scenario with `consume` as a steps template.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-input-artifact-default-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: consume
                            template: consume
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      steps:
                        - - name: check
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{inputs.artifacts.data}}'
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from default" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "Steps template: declared S3 default of an input artifact must be used when the caller passes none" + "\n" + outcome.summary());
    }

    @Test
    void dagTemplateInputArtifactArgumentOverridesDefault() throws Exception {
        // `consume` declares an S3 default, but the caller passes `produce`'s output artifact —
        // the passed artifact must win.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-input-artifact-override-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: produce
                            template: produce
                          - name: consume
                            depends: produce
                            template: consume
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{tasks.produce.outputs.artifacts.data}}'
                    - name: produce
                      outputs:
                        artifacts:
                          - name: data
                            path: /tmp/out.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo "from caller" > /tmp/out.txt
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      dag:
                        tasks:
                          - name: check
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{inputs.artifacts.data}}'
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from caller" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "DAG template: an artifact passed by the caller must override the declared S3 default" + "\n" + outcome.summary());
    }

    @Test
    void dagTaskPodInputArtifactArgumentOverridesDefault() throws Exception {
        // Here the default sits on a pod template. The caller's argument must still win;
        // the default must not be downloaded over it.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-pod-input-artifact-override-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: produce
                            template: produce
                          - name: check
                            depends: produce
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{tasks.produce.outputs.artifacts.data}}'
                    - name: produce
                      outputs:
                        artifacts:
                          - name: data
                            path: /tmp/out.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo "from caller" > /tmp/out.txt
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from caller" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "DAG task: an artifact passed to a pod template must override its declared S3 default" + "\n" + outcome.summary());
    }

    @Test
    void stepsPodInputArtifactArgumentOverridesDefault() throws Exception {
        // Same scenario with a steps caller.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-pod-input-artifact-override-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: produce
                            template: produce
                        - - name: check
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{steps.produce.outputs.artifacts.data}}'
                    - name: produce
                      outputs:
                        artifacts:
                          - name: data
                            path: /tmp/out.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo "from caller" > /tmp/out.txt
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from caller" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "Steps step: an artifact passed to a pod template must override its declared S3 default" + "\n" + outcome.summary());
    }

    @Test
    void dagNestedTemplateDoesNotInheritEnclosingTemplatesInputArtifact() throws Exception {
        // `outer` receives `data` = "from caller". It calls `inner` without passing any
        // artifact. `inner` declares its own `data` with an S3 default, so inside `inner`
        // `{{inputs.artifacts.data}}` must be the default, not `outer`'s artifact.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-nested-input-artifact-scope-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: produce
                            template: produce
                          - name: outer
                            depends: produce
                            template: outer
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{tasks.produce.outputs.artifacts.data}}'
                    - name: produce
                      outputs:
                        artifacts:
                          - name: data
                            path: /tmp/out.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo "from caller" > /tmp/out.txt
                    - name: outer
                      inputs:
                        artifacts:
                          - name: data
                      dag:
                        tasks:
                          - name: inner
                            template: inner
                    - name: inner
                      inputs:
                        artifacts:
                          - name: data
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      dag:
                        tasks:
                          - name: check
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{inputs.artifacts.data}}'
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from default" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "DAG: a nested template must resolve its own input artifact default, not the enclosing template's artifact" + "\n" + outcome.summary());
    }

    @Test
    void stepsNestedTemplateDoesNotInheritEnclosingTemplatesInputArtifact() throws Exception {
        // Same scenario with steps templates.
        String workflowYaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-nested-input-artifact-scope-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: produce
                            template: produce
                        - - name: outer
                            template: outer
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{steps.produce.outputs.artifacts.data}}'
                    - name: produce
                      outputs:
                        artifacts:
                          - name: data
                            path: /tmp/out.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo "from caller" > /tmp/out.txt
                    - name: outer
                      inputs:
                        artifacts:
                          - name: data
                      steps:
                        - - name: inner
                            template: inner
                    - name: inner
                      inputs:
                        artifacts:
                          - name: data
                            s3:
                              endpoint: '%s'
                              insecure: true
                              bucket: input-artifact-default-test
                              key: default/data.txt
                              accessKeySecret:
                                name: minio-creds
                                key: access-key
                              secretKeySecret:
                                name: minio-creds
                                key: secret-key
                            archive:
                              none: {}
                      steps:
                        - - name: check
                            template: check
                            arguments:
                              artifacts:
                                - name: data
                                  from: '{{inputs.artifacts.data}}'
                    - name: check
                      inputs:
                        artifacts:
                          - name: data
                            path: /data/input.txt
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -qx "from default" /data/input.txt
                """;

        WorkflowOutcome outcome = run(workflowYaml);
        assertTrue(outcome.succeeded(),
                () -> "Steps: a nested template must resolve its own input artifact default, not the enclosing template's artifact" + "\n" + outcome.summary());
    }

    private WorkflowOutcome run(String workflowYaml) throws Exception {
        return runWorkflow(workflowYaml.formatted(s3Endpoint()));
    }
}
