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
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Regression test for: {@code extractArtifact} resolves tar entry names with
 * {@link Path#resolve(String)} without stripping leading {@code ./} (or {@code /}) prefixes.
 *
 * <p>The reproducer uses a checker step whose input and output artifact share the same
 * container path ({@code /data}). The executor bind-mounts the upstream directory at
 * {@code /data}; {@code extractArtifact} then calls {@code copyArchiveFromContainerCmd} on
 * that bind-mounted path. The resulting tar entries may carry a leading prefix that causes
 * {@link Path#resolve} to produce a path outside {@code tempDir}, leading to
 * {@link java.nio.file.AccessDeniedException} or silent data loss.
 */
class OutputArtifactExtractionTest {

    private static final Logger log = LoggerFactory.getLogger(OutputArtifactExtractionTest.class);

    @Test
    void directoryArtifactPassedThroughReadOnlyCheckerStep() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/output-artifact-extract.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            log.info(WorkflowSummary.format(run));
            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            PodRun checker = (PodRun) dag.get("check-data");
            assertThat("checker succeeded", checker.succeeded(), is(true));
            assertThat("gtfs-data artifact was collected by checker",
                    checker.collectedArtifacts().containsKey("gtfs-data"), is(true));

            PodRun verifier = (PodRun) dag.get("verify-data");
            assertThat("verifier succeeded", verifier.succeeded(), is(true));
            assertThat("verifier output contains artifact-content",
                    verifier.logs().trim(), containsString("artifact-content"));
        }
    }
}
