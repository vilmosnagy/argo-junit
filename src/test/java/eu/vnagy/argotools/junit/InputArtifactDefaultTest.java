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
import eu.vnagy.argotools.junit.testutil.SiloContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/**
 * Verifies Argo's default semantics for input artifacts that declare a location
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
 * @see <a href="https://github.com/vilmosnagy/argo-junit/issues/167">#167</a>
 */
class InputArtifactDefaultTest {

    static final String BUCKET = "input-artifact-default-test";

    static KwokContainer kwok;
    static SiloContainer minio;

    @BeforeAll
    static void setup() {
        minio = new SiloContainer();
        minio.start();
        minio.createBucket(BUCKET);

        try (S3Client client = minio.createClient()) {
            client.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key("default/data.txt").build(),
                    RequestBody.fromString("from default"));
        }

        kwok = new KwokContainer();
        kwok.start();
        kwok.createClient()
                .secrets()
                .inNamespace("default")
                .resource(minio.credentialsSecret("minio-creds", "access-key", "secret-key"))
                .create();
    }

    @AfterAll
    static void tearDown() {
        if (kwok != null) kwok.stop();
        if (minio != null) minio.stop();
    }

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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG template: declared S3 default of an input artifact must be used when the caller passes none");
        }
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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Steps template: declared S3 default of an input artifact must be used when the caller passes none");
        }
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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG template: an artifact passed by the caller must override the declared S3 default");
        }
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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG task: an artifact passed to a pod template must override its declared S3 default");
        }
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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Steps step: an artifact passed to a pod template must override its declared S3 default");
        }
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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "DAG: a nested template must resolve its own input artifact default, not the enclosing template's artifact");
        }
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
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
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
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
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

        Workflow wf = patchEndpointAndBucket(workflowYaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute(Duration.ofMinutes(10))) {
            assertTrue(run.succeeded(),
                    "Steps: a nested template must resolve its own input artifact default, not the enclosing template's artifact");
        }
    }

    private Workflow patchEndpointAndBucket(String yaml) throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "s3-endpoint".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(minio.endpoint());
        wf.getSpec().getArguments().getParameters().stream()
                .filter(p -> "s3-bucket".equals(p.getName()))
                .findFirst().orElseThrow()
                .setValue(BUCKET);
        return wf;
    }
}
