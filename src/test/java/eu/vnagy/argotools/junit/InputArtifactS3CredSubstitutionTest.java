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
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {{workflow.parameters.*}} placeholders in a template's own
 * {@code inputs.artifacts} S3 credential fields are substituted before the artifact
 * is downloaded.
 *
 * <p>This is distinct from {@link DirectS3ArtifactArgTest}: here the S3 location (including
 * the credential secret names) is declared directly in {@code template.inputs.artifacts},
 * not passed down as a task/step argument. Before the fix, {@code PodRun} passed the raw
 * artifact to the driver without calling {@code ExecutionContext.substituteArtifact}, so
 * the secret lookup used the literal {@code {{...}}} string and threw a
 * {@code URISyntaxException}.
 */
class InputArtifactS3CredSubstitutionTest {

    static final String BUCKET = "s3-creds-subst-test";
    static final String SECRET_NAME = "minio-credentials";

    static KwokContainer kwok;
    static MinioContainer minio;

    @BeforeAll
    static void setup() {
        minio = new MinioContainer();
        minio.start();
        minio.createBucket(BUCKET);
        try (S3Client client = minio.createClient()) {
            client.putObject(
                    PutObjectRequest.builder().bucket(BUCKET).key("input/data.txt").build(),
                    RequestBody.fromString("s3 creds substitution test"));
        }

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
        if (kwok != null) kwok.stop();
        if (minio != null) minio.stop();
    }

    @Test
    void dagTemplateInputArtifactS3CredentialIsSubstituted() throws Exception {
        // The consume template owns its S3 location; the credential secret name comes from
        // {{workflow.parameters.creds-secret}}. No artifact argument is passed from the DAG.
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: dag-s3-cred-subst-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                      - name: creds-secret
                        value: placeholder
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: run
                            template: consume
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: input/data.txt
                              accessKeySecret:
                                name: '{{workflow.parameters.creds-secret}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.creds-secret}}'
                                key: secret-key
                            archive:
                              none: {}
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "s3 creds substitution test" /data/input.txt
                """;

        Workflow wf = patchParams(yaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertTrue(run.succeeded(),
                    "DAG: {{workflow.parameters.*}} in template inputs.artifacts S3 creds must be substituted before download");
        }
    }

    @Test
    void stepsTemplateInputArtifactS3CredentialIsSubstituted() throws Exception {
        // Same scenario with a steps entrypoint instead of a DAG.
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: steps-s3-cred-subst-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: s3-endpoint
                        value: placeholder
                      - name: s3-bucket
                        value: placeholder
                      - name: creds-secret
                        value: placeholder
                  templates:
                    - name: main
                      steps:
                        - - name: run
                            template: consume
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data-file
                            path: /data/input.txt
                            s3:
                              endpoint: '{{workflow.parameters.s3-endpoint}}'
                              insecure: true
                              bucket: '{{workflow.parameters.s3-bucket}}'
                              key: input/data.txt
                              accessKeySecret:
                                name: '{{workflow.parameters.creds-secret}}'
                                key: access-key
                              secretKeySecret:
                                name: '{{workflow.parameters.creds-secret}}'
                                key: secret-key
                            archive:
                              none: {}
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          grep -q "s3 creds substitution test" /data/input.txt
                """;

        Workflow wf = patchParams(yaml);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).withKwok(kwok).execute()) {
            assertTrue(run.succeeded(),
                    "Steps: {{workflow.parameters.*}} in template inputs.artifacts S3 creds must be substituted before download");
        }
    }

    private Workflow patchParams(String yaml) throws Exception {
        Workflow wf = ArgoWorkflowExecutor.yamlMapper().readValue(yaml, Workflow.class);
        wf.getSpec().getArguments().getParameters().forEach(p -> {
            switch (p.getName()) {
                case "s3-endpoint"   -> p.setValue(minio.endpoint());
                case "s3-bucket"     -> p.setValue(BUCKET);
                case "creds-secret"  -> p.setValue(SECRET_NAME);
            }
        });
        return wf;
    }
}
