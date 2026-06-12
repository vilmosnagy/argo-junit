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
import eu.vnagy.argotools.junit.executor.DagRun;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.StepsRun;
import eu.vnagy.argotools.junit.executor.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class ConditionalArtifactsTest {

    @Test
    void stepsConditionalArtifactPicksCorrectBranch() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/conditional-artifacts.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            StepsRun steps = (StepsRun) run.entrypoint();
            PodRun flipCoin = (PodRun) steps.get("flip-coin");
            PodRun heads    = (PodRun) steps.get("heads");
            PodRun tails    = (PodRun) steps.get("tails");

            assertThat("flip-coin succeeded", flipCoin.succeeded(), is(true));

            boolean coinIsHeads   = "heads".equals(flipCoin.logs().trim());
            WorkflowNode ran      = coinIsHeads ? heads : tails;
            WorkflowNode skipped  = coinIsHeads ? tails : heads;
            String expectedText   = coinIsHeads ? "it was heads" : "it was tails";

            assertThat("winning branch succeeded", ran.succeeded(), is(true));
            assertThat("losing branch was not run", skipped.succeeded(), is(false));

            // The ran branch must have collected its result artifact
            Path branchArtifact = ((PodRun) ran).collectedArtifacts().get("result");
            assertThat("ran branch has result artifact", branchArtifact, notNullValue());
            assertThat("branch artifact content",
                    Files.readString(branchArtifact).trim(), is(expectedText));

            // The main template propagates the winning artifact via fromExpression:
            //   steps['flip-coin'].outputs.result == 'heads'
            //     ? steps.heads.outputs.artifacts.result
            //     : steps.tails.outputs.artifacts.result
            assertThat("main result artifact is present",
                    steps.collectedArtifacts().containsKey("result"), is(true));
            assertThat("main result artifact content",
                    Files.readString(steps.collectedArtifacts().get("result")).trim(), is(expectedText));
        }
    }

    @Test
    void dagConditionalArtifactPicksCorrectBranch() throws Exception {
        try (var run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-conditional-artifacts.yaml").toURI()))
                .execute(Duration.ofMinutes(10))) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag      = (DagRun) run.entrypoint();
            PodRun flipCoin = (PodRun) dag.get("flip-coin");
            PodRun heads    = (PodRun) dag.get("heads");
            PodRun tails    = (PodRun) dag.get("tails");

            assertThat("flip-coin succeeded", flipCoin.succeeded(), is(true));

            boolean coinIsHeads  = "heads".equals(flipCoin.logs().trim());
            WorkflowNode ran     = coinIsHeads ? heads : tails;
            WorkflowNode skipped = coinIsHeads ? tails : heads;
            String expectedText  = coinIsHeads ? "it was heads" : "it was tails";

            assertThat("winning branch succeeded", ran.succeeded(), is(true));
            assertThat("losing branch was not run", skipped.succeeded(), is(false));

            // The ran branch must have collected its result artifact
            Path branchArtifact = ((PodRun) ran).collectedArtifacts().get("result");
            assertThat("ran branch has result artifact", branchArtifact, notNullValue());
            assertThat("branch artifact content",
                    Files.readString(branchArtifact).trim(), is(expectedText));

            // The main template propagates the winning artifact via fromExpression:
            //   tasks['flip-coin'].outputs.result == 'heads'
            //     ? tasks.heads.outputs.artifacts.result
            //     : tasks.tails.outputs.artifacts.result
            assertThat("main result artifact is present",
                    dag.collectedArtifacts().containsKey("result"), is(true));
            assertThat("main result artifact content",
                    Files.readString(dag.collectedArtifacts().get("result")).trim(), is(expectedText));
        }
    }
}
