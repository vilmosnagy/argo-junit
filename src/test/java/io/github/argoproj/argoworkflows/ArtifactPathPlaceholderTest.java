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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class ArtifactPathPlaceholderTest {

    @Test
    void artifactAndOutputParamPathsAreSubstituted() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/artifact-path-placeholders.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            PodRun headLines = (PodRun) run.entrypoint();
            assertThat("head-lines succeeded", headLines.succeeded(), is(true));

            // {{outputs.artifacts.text.path}} must have been substituted to /outputs/text/data
            // so the container wrote the first 3 lines of the input there
            Path textArtifact = headLines.collectedArtifacts().get("text");
            assertThat("text output artifact collected", textArtifact, notNullValue());
            assertThat("text artifact contains first 3 lines",
                    Files.readString(textArtifact).trim(), is("1\n2\n3"));

            // {{outputs.parameters.actual-lines-count.path}} must have been substituted so
            // wc -l wrote "3" there and the executor read it back as an output parameter
            assertThat("actual-lines-count output parameter collected",
                    headLines.collectedOutputParams().containsKey("actual-lines-count"), is(true));
            assertThat("actual-lines-count value",
                    headLines.collectedOutputParams().get("actual-lines-count").trim(), is("3"));
        }
    }
}
