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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import eu.vnagy.argotools.junit.util.WorkflowSummary;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.output.OutputFrame;

import java.nio.file.Path;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import java.time.Duration;

class WorkflowExecutionTest {

    @Test
    void executesHelloWorld() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/hello-world.yaml").toURI()))
                .execute(Duration.ofMinutes(10));

        assertThat(run.succeeded(), is(true));

        PodRun pod = (PodRun) run.entrypoint();
        assertThat(pod.exitCode(), is(0));
        assertThat(pod.logs(), containsString("hello world"));
        assertThat(pod.container().getLogs(OutputFrame.OutputType.STDOUT), containsString("hello world"));
    }

    @Test
    void executesCoinflip() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/coinflip.yaml").toURI()))
                .execute(Duration.ofMinutes(10));

        assertThat(run.succeeded(), is(true));

        StepsRun coinflip = (StepsRun) run.entrypoint();

        PodRun flipCoin = (PodRun) coinflip.get("flip-coin");
        assertThat(flipCoin.exitCode(), is(0));
        assertThat(flipCoin.outputResult(),
                anyOf(is(Optional.of("heads")), is(Optional.of("tails"))));
        assertThat(flipCoin.container().getLogs(OutputFrame.OutputType.STDOUT).trim(),
                anyOf(is("heads"), is("tails")));

        PodRun heads = (PodRun) coinflip.get("heads");
        PodRun tails = (PodRun) coinflip.get("tails");
        assertThat("exactly one branch runs", heads.skipped() ^ tails.skipped(), is(true));
        if (!heads.skipped()) {
            assertThat(heads.container().getLogs(OutputFrame.OutputType.STDOUT), containsString("it was heads"));
        }
        if (!tails.skipped()) {
            assertThat(tails.container().getLogs(OutputFrame.OutputType.STDOUT), containsString("it was tails"));
        }

        System.out.println(WorkflowSummary.format(run));
    }

    @Test
    void executesDagDiamond() throws Exception {
        WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/examples/dag-diamond.yaml").toURI()))
                .execute(Duration.ofMinutes(10));

        assertThat(run.succeeded(), is(true));

        DagRun diamond = (DagRun) run.entrypoint();

        PodRun a = (PodRun) diamond.get("A");
        assertThat(a.exitCode(), is(0));
        assertThat(a.logs(), containsString("A"));
        assertThat(a.container().getLogs(OutputFrame.OutputType.STDOUT).trim(), is("A"));

        PodRun b = (PodRun) diamond.get("B");
        assertThat(b.exitCode(), is(0));
        assertThat(b.logs(), containsString("B"));
        assertThat(b.container().getLogs(OutputFrame.OutputType.STDOUT).trim(), is("B"));

        PodRun c = (PodRun) diamond.get("C");
        assertThat(c.exitCode(), is(0));
        assertThat(c.logs(), containsString("C"));
        assertThat(c.container().getLogs(OutputFrame.OutputType.STDOUT).trim(), is("C"));

        PodRun d = (PodRun) diamond.get("D");
        assertThat(d.exitCode(), is(0));
        assertThat(d.logs(), containsString("D"));
        assertThat(d.container().getLogs(OutputFrame.OutputType.STDOUT).trim(), is("D"));
    }
}
