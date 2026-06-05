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
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

class EmptyDirVolumeTest {

    @Test
    void emptyDirVolumeIsMounted() throws Exception {
        Path yaml = Path.of(getClass().getResource("/emptydir-mount-check.yaml").toURI());
        try (WorkflowRun run = ArgoWorkflowExecutor.from(yaml).execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("Volume mounted and found"));
        }
    }
}
