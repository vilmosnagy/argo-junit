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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class ArtifactPassingTest {

  @Test
  void fileArtifactPassedBetweenSteps() throws Exception {
    try (WorkflowRun run = ArgoWorkflowExecutor
            .from(Path.of(getClass().getResource("/examples/artifact-passing.yaml").toURI()))
            .execute()) {

      assertThat("workflow succeeded", run.succeeded(), is(true));

      StepsRun steps = (StepsRun) run.entrypoint();

      PodRun generator = (PodRun) steps.get("generate-artifact");
      assertThat("generator succeeded", generator.succeeded(), is(true));
      assertThat("generator collected hello-art artifact",
              generator.collectedArtifacts().containsKey("hello-art"), is(true));

      PodRun consumer = (PodRun) steps.get("consume-artifact");
      assertThat("consumer succeeded", consumer.succeeded(), is(true));
      assertThat("consumer output contains the artifact content",
              consumer.logs().trim(), containsString("hello world"));
    }
  }

}
