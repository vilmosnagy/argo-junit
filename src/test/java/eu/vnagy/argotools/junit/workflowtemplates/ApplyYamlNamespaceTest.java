package eu.vnagy.argotools.junit.workflowtemplates;

import eu.vnagy.argotools.junit.kwok.ArgoKwok;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class ApplyYamlNamespaceTest {

    static ArgoKwok argoKwok;
    static KubernetesClient k8s;

    @BeforeAll
    static void setup() {
        argoKwok = new ArgoKwok();
        argoKwok.start();
        k8s = argoKwok.createClient();

        String yaml = """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: test-cm
                data:
                  key: value
                """;
        argoKwok.applyYaml(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
                item -> "ConfigMap".equals(item.getKind()));
    }

    @AfterAll
    static void tearDown() {
        if (argoKwok != null) argoKwok.stop();
    }

    @Test
    void configMapWithoutNamespaceLandsInDefault() {
        var cm = k8s.configMaps().inNamespace("default").withName("test-cm").get();
        assertThat("ConfigMap without namespace in metadata should land in default", cm, is(notNullValue()));
    }
}
