package io.github.argoproj.argoworkflows;

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
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class GlobalOutputsTest {

    @Test
    void globalParameterIsAvailableAfterExecution() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/global-outputs.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat(run.succeeded(), is(true));

            // global-output template writes "hello world" and exports it as my-global-param
            assertThat(run.globalParameter("my-global-param"), is(Optional.of("hello world")));

            // The second step group in the entrypoint re-uses the global param
            StepsRun generateGlobals = (StepsRun) run.entrypoint();
            StepsRun inlineConsume = (StepsRun) generateGlobals.get("consume-globals");
            PodRun inlineConsumeParam = (PodRun) inlineConsume.get("consume-global-param");
            assertThat(inlineConsumeParam.logs(), containsString("hello world"));

            // The onExit handler also consumes the global param
            StepsRun exitConsume = (StepsRun) run.exitHandler();
            PodRun exitConsumeParam = (PodRun) exitConsume.get("consume-global-param");
            assertThat(exitConsumeParam.logs(), containsString("hello world"));
        }
    }
}
