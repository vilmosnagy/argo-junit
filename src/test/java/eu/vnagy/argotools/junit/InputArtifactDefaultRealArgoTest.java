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

import eu.vnagy.argotools.junit.testutil.KindArgoCluster;
import eu.vnagy.argotools.junit.testutil.SiloContainer;
import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.time.Duration;

/**
 * Runs the input artifact default scenarios on a real Argo controller in kind, to check that
 * the assertions in {@link InputArtifactDefaultBase} match how Argo actually behaves.
 *
 * <p><b>Requirements:</b> privileged containers (rootful Docker, or Podman — rootless works) and
 * network access to pull the kind node and Argo images. Not run in CI.
 *
 * @see KindArgoCluster
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class InputArtifactDefaultRealArgoTest extends InputArtifactDefaultBase {

    static KindArgoCluster argo;

    @BeforeAll
    static void setUpArgo() {
        argo = KindArgoCluster.shared();
        argo.attach(minio, SILO_ALIAS);
        argo.createSecret(minio.credentialsSecret(SECRET_NAME, "access-key", "secret-key"));
        argo.useS3ArtifactRepository(inClusterS3Endpoint(), BUCKET, SECRET_NAME);
    }

    @Override
    protected String s3Endpoint() {
        return inClusterS3Endpoint();
    }

    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        return argo.run(workflowYaml, Duration.ofMinutes(5));
    }

    private static String inClusterS3Endpoint() {
        return SILO_ALIAS + ":" + SiloContainer.MINIO_PORT;
    }
}
