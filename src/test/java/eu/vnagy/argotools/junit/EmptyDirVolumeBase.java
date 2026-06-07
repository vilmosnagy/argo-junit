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
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Shared fixture and test scenario for the {@code emptyDir} volume-mount test.
 *
 * <p>Concrete subclasses implement {@link #configure(ArgoWorkflowExecutor)} to select the
 * Docker daemon that step containers run against. The {@code emptyDir} code path uses
 * {@code withFileSystemBind} with an intentionally empty directory, so it behaves correctly
 * on a remote daemon (DinD creates an empty directory on its own filesystem — the expected
 * result). Both the local and DinD variants are expected to pass.
 */
public abstract class EmptyDirVolumeBase {

    protected abstract ArgoWorkflowExecutor configure(ArgoWorkflowExecutor executor);

    @Test
    void emptyDirVolumeIsMounted() throws Exception {
        Path yaml = Path.of(getClass().getResource("/emptydir-mount-check.yaml").toURI());
        try (WorkflowRun run = configure(ArgoWorkflowExecutor.from(yaml))
                .execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("Volume mounted and found"));
        }
    }
}
