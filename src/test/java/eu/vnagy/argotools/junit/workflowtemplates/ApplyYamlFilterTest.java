package eu.vnagy.argotools.junit.workflowtemplates;

import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

class ApplyYamlFilterTest {

    static ArgoKwok argoKwok;
    static KubernetesClient k8s;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        k8s = argoKwok.createClient();
        argoKwok.applyYaml(
                "/wftemplate/filter/two-workflow-templates.yaml",
                item -> "wt-alpha".equals(item.getMetadata().getName()));
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void matchingTemplateIsApplied() {
        var wtAlpha = k8s.genericKubernetesResources(ArgoWorkflowExecutor.WORKFLOW_TEMPLATE_CTX)
                .inNamespace("default").withName("wt-alpha").get();
        assertThat("wt-alpha should be present", wtAlpha, is(notNullValue()));
    }

    @Test
    void filteredOutTemplateIsAbsent() {
        var wtBeta = k8s.genericKubernetesResources(ArgoWorkflowExecutor.WORKFLOW_TEMPLATE_CTX)
                .inNamespace("default").withName("wt-beta").get();
        assertThat("wt-beta should not be present", wtBeta, is(nullValue()));
    }
}
