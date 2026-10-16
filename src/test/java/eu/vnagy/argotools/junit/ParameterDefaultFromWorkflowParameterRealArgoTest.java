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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.time.Duration;

/**
 * Runs the parameter-default-from-workflow-parameter scenarios on a real Argo controller in
 * kind, to check that the assertions in {@link ParameterDefaultFromWorkflowParameterBase} match
 * how Argo actually behaves. Not run in CI; see {@link InputArtifactDefaultRealArgoTest} for the
 * requirements.
 *
 * @see KindArgoCluster
 */
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class ParameterDefaultFromWorkflowParameterRealArgoTest extends ParameterDefaultFromWorkflowParameterBase {

    static KindArgoCluster argo;

    @BeforeAll
    static void setUpArgo() {
        argo = KindArgoCluster.shared();
    }

    @Override
    protected WorkflowOutcome runWorkflow(String workflowYaml) throws Exception {
        return argo.run(workflowYaml, Duration.ofMinutes(5));
    }
}
