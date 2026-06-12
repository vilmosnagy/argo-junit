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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@code outputs.parameters[].valueFrom.parameter} in a sub-DAG.
 *
 * <p>A DAG template can forward a child task's output parameter to its own outputs via:
 *
 * <pre>
 *   outputs:
 *     parameters:
 *       - name: greeting
 *         valueFrom:
 *           parameter: '{{tasks.inner.outputs.parameters.greeting}}'
 * </pre>
 *
 * <p>If the executor does not resolve {@code valueFrom.parameter}, the sub-DAG's
 * {@code collectedOutputParams} stays empty, nothing is registered in the parent's
 * {@code taskOutputParams}, and the downstream task receives the raw placeholder
 * {@code {{tasks.compute.outputs.parameters.greeting}}} as its argument value.
 * The verify script in the workflow exits non-zero when it sees an unresolved
 * {@code {{…}}} in its input, which surfaces as a failed workflow run.
 */
class DagSubDagValueFromParameterTest {

    @Test
    void subDagOutputParameterForwardedViaValueFromParameter() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/dag-subdad-valuefrom-parameter.yaml").toURI()))
                .execute(Duration.ofMinutes(5))) {
            assertTrue(run.succeeded(),
                    "Sub-DAG output forwarded via valueFrom.parameter must be resolved before " +
                    "substitution into downstream task arguments — an unresolved placeholder " +
                    "causes the verify script to exit non-zero");
        }
    }
}
