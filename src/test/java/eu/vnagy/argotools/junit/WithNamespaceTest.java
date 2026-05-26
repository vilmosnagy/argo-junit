package eu.vnagy.argotools.junit;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.executor.PodRun;
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

class WithNamespaceTest {

    @Test
    void configMapReadFromCustomNamespace() throws Exception {
        var executor = ArgoWorkflowExecutor.from(
                Path.of(getClass().getResource("/examples/arguments-parameters-from-configmap.yaml").toURI()));

        var k8s = executor.getKubernetesClient();

        // Same ConfigMap name in default namespace — must not be picked up
        k8s.configMaps()
                .inNamespace("default")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("simple-parameters").endMetadata()
                        .addToData("msg", "wrong namespace")
                        .build())
                .create();

        // Same ConfigMap name in the custom namespace — this is the one we expect
        String customNs = "argotest";
        k8s.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata().withName(customNs).endMetadata()
                        .build())
                .create();
        k8s.configMaps()
                .inNamespace(customNs)
                .resource(new ConfigMapBuilder()
                        .withNewMetadata().withName("simple-parameters").endMetadata()
                        .addToData("msg", "custom namespace value")
                        .build())
                .create();

        executor.withNamespace(customNs);
        try (WorkflowRun run = executor.execute()) {
            assertThat(run.succeeded(), is(true));
            assertThat(run.entrypoint(), instanceOf(PodRun.class));
            assertThat(((PodRun) run.entrypoint()).logs().strip(), is("custom namespace value"));
        }
    }
}
