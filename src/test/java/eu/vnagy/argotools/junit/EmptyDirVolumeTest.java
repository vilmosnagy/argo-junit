package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

class EmptyDirVolumeTest {

    @Test
    void emptyDirVolumeIsMounted() throws Exception {
        Path yaml = Path.of(getClass().getResource("/emptydir-mount-check.yaml").toURI());
        try (WorkflowRun run = ArgoWorkflowExecutor.from(yaml).execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(((PodRun) run.entrypoint()).logs(), containsString("Volume mounted and found"));
        }
    }
}
