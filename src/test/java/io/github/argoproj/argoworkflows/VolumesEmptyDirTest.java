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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import java.time.Duration;

class VolumesEmptyDirTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Smoke-tests that the upstream example workflow completes without error.
     *
     * <p>The upstream script uses {@code [[ -n $vol_found ]]} with an unquoted variable.
     * Alpine's busybox {@code /bin/sh} word-splits unquoted expansions inside {@code [[},
     * so mount output like {@code overlay on /mnt/vol ...} causes {@code sh: on: unknown operand}
     * and the else-branch fires even though the volume is mounted. The exit code is still 0,
     * so workflow success is a valid signal. The actual mount assertion lives in
     * {@link eu.vnagy.argotools.junit.EmptyDirVolumeTest} using a POSIX-safe fixture.
     */
    @Test
    void emptyDirVolumeIsMounted() throws Exception {
        Workflow wf = YAML.readValue(
                getClass().getResource("/examples/volumes-emptydir.yaml"), Workflow.class);
        try (WorkflowRun run = ArgoWorkflowExecutor.from(wf).execute(Duration.ofMinutes(10))) {
            assertThat(run.succeeded(), is(true));
        }
    }
}
