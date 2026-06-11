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
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class LoopRunSummaryTest {

    @Test
    void loopRunAppearsInSummaryWithItemDurationsAndCids() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/withparam-loop.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Succeeded

                    STEP                     DURATION  MESSAGE
                     ✔ main
                     ├─✔ produce-list        {duration}  {cid}
                     └─✔ process-each
                        ├─✔ process-each[0]  {duration}  {"label":"first","value":"1"}  {cid}
                        ├─✔ process-each[1]  {duration}  {"label":"second","value":"2"}  {cid}
                        └─✔ process-each[2]  {duration}  {"label":"third","value":"3"}  {cid}
                    """));
        }
    }

    @Test
    void longItemLabelIsTrimmedAt150InSummary() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/withparam-loop-long-item.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            // {"description":"<137 x's>"} = 155 chars; substring(0,150) + "..." trims it
            String label155 = "{\"description\":\"" + "x".repeat(137) + "\"}";
            assertThat("fixture label is 155 chars", label155.length(), is(155));

            String truncated = label155.substring(0, 150) + "...";
            assertThat(normalizeDurations(WorkflowSummary.format(run)), equalTo("""
                    Status:  Succeeded

                    STEP                     DURATION  MESSAGE
                     ✔ main
                     └─✔ process-each
                        └─✔ process-each[0]  {duration}  %s  {cid}
                    """.formatted(truncated)));
        }
    }

    private static String normalizeDurations(String summary) {
        return summary
                .replaceAll("\\d+m \\d+s|\\d+s", "{duration}")
                .replaceAll("\\{duration} {2,}", "{duration}  ")
                .replaceAll("[0-9a-f]{12}", "{cid}")
                .replaceAll("\\(Service: S3, Status Code: \\d+[^)]*\\)", "{s3-sdk}");
    }
}
