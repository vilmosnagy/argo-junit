package eu.vnagy.argotools.junit.executor;

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

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ArtifactLifecycleTest {

    @Test
    void unconsumedArtifactNotExtracted() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/unconsumed-artifact.yaml").toURI()))
                .execute()) {

            assertThat("workflow succeeded", run.succeeded(), is(true));
            try (var ls = Files.list(run.tmpDir)) {
                assertThat("tmpDir is empty — unconsumed output artifact was not extracted",
                        ls.count(), is(0L));
            }
        }
    }

    @Test
    void tmpDirDeletedAfterClose() throws Exception {
        Path tmpDir;
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/artifact-dag.yaml").toURI()))
                .execute()) {

            tmpDir = run.tmpDir;
            assertThat("workflow succeeded", run.succeeded(), is(true));
            try (var ls = Files.list(tmpDir)) {
                assertThat("tmpDir contains extracted artifacts while run is open",
                        ls.count() > 0, is(true));
            }
        }
        assertThat("tmpDir deleted after WorkflowRun.close()", Files.exists(tmpDir), is(false));
    }
}
