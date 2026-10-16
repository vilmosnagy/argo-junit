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
import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;

/**
 * Runs the env-var-name substitution scenarios on a real Argo controller in kind, to check that
 * the assertions in {@link EnvVarNameSubstitutionBase} match how Argo actually behaves. Not run
 * in CI; see {@link InputArtifactDefaultRealArgoTest} for the requirements.
 *
 * @see KindArgoCluster
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class EnvVarNameSubstitutionRealArgoTest extends EnvVarNameSubstitutionBase {

    static KindArgoCluster argo;

    @BeforeAll
    static void setUpArgo() {
        argo = KindArgoCluster.shared();
        argo.createSecret(new SecretBuilder()
                .withNewMetadata().withName(SECRET_NAME).endMetadata()
                .withStringData(Map.of(SECRET_KEY, SECRET_VALUE))
                .build());
        argo.createConfigMap(new ConfigMapBuilder()
                .withNewMetadata().withName(CONFIG_MAP_NAME).endMetadata()
                .withData(Map.of(CONFIG_MAP_KEY, CONFIG_MAP_VALUE))
                .build());
    }

    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        return argo.run(workflowYaml, Duration.ofMinutes(5));
    }
}
