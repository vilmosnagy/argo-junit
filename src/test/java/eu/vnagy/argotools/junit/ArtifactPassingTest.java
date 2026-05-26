package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.DagRun;
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

    @Test
    void fileArtifactPassedBetweenDagTasks() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/artifact-dag.yaml").toURI()))
                .execute()) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            PodRun generator = (PodRun) dag.get("generate-artifact");
            assertThat("generator succeeded", generator.succeeded(), is(true));
            assertThat("generator collected hello-art artifact",
                    generator.collectedArtifacts().containsKey("hello-art"), is(true));

            PodRun consumer = (PodRun) dag.get("consume-artifact");
            assertThat("consumer succeeded", consumer.succeeded(), is(true));
            assertThat("consumer output contains the artifact content",
                    consumer.logs().trim(), containsString("hello world"));
        }
    }

    @Test
    void directoryArtifactPassedBetweenSteps() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/artifact-dir-steps.yaml").toURI()))
                .execute()) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            StepsRun steps = (StepsRun) run.entrypoint();

            PodRun generator = (PodRun) steps.get("generate-files");
            assertThat("generator succeeded", generator.succeeded(), is(true));
            assertThat("generator collected files-dir artifact",
                    generator.collectedArtifacts().containsKey("files-dir"), is(true));
            assertThat("collected artifact is a directory",
                    generator.collectedArtifacts().get("files-dir").toFile().isDirectory(), is(true));

            PodRun consumer = (PodRun) steps.get("print-files");
            assertThat("consumer succeeded", consumer.succeeded(), is(true));
            assertThat("consumer output contains file-one", consumer.logs().trim(), containsString("file-one"));
            assertThat("consumer output contains file-two", consumer.logs().trim(), containsString("file-two"));
        }
    }

    @Test
    void directoryArtifactPassedBetweenDagTasks() throws Exception {
        try (WorkflowRun run = ArgoWorkflowExecutor
                .from(Path.of(getClass().getResource("/artifact-dir-dag.yaml").toURI()))
                .execute()) {

            assertThat("workflow succeeded", run.succeeded(), is(true));

            DagRun dag = (DagRun) run.entrypoint();

            PodRun generator = (PodRun) dag.get("generate-files");
            assertThat("generator succeeded", generator.succeeded(), is(true));
            assertThat("generator collected files-dir artifact",
                    generator.collectedArtifacts().containsKey("files-dir"), is(true));
            assertThat("collected artifact is a directory",
                    generator.collectedArtifacts().get("files-dir").toFile().isDirectory(), is(true));

            PodRun consumer = (PodRun) dag.get("print-files");
            assertThat("consumer succeeded", consumer.succeeded(), is(true));
            assertThat("consumer output contains file-one", consumer.logs().trim(), containsString("file-one"));
            assertThat("consumer output contains file-two", consumer.logs().trim(), containsString("file-two"));
        }
    }
}
