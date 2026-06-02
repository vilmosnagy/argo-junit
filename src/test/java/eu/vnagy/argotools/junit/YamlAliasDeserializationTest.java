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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.executor.ArgoWorkflowExecutor;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that YAML anchor aliases in workflow definitions are correctly resolved
 * by {@link ArgoWorkflowExecutor#yamlMapper()}.
 *
 * <p>Jackson's plain {@code YAMLFactory} does not resolve YAML aliases: for POJO-type fields
 * it emits a scalar string (the anchor name) and throws {@code MismatchedInputException}; for
 * String-type fields it silently assigns the anchor <em>name</em> instead of the anchor
 * <em>value</em>. Both bugs are fixed by {@code YAMLAnchorReplayingFactory} (Jackson 2.19+),
 * which replays the anchored token stream on every alias reference.
 */
class YamlAliasDeserializationTest {

    private static final String POJO_ALIAS_YAML = """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: alias-test
            spec:
              entrypoint: step
              x-no-retry: &no-retry
                limit: 0
              templates:
                - name: step
                  retryStrategy: *no-retry
                  container:
                    image: alpine
                    command: [echo, hello]
            """;

    private static final String STRING_ALIAS_YAML = """
            apiVersion: argoproj.io/v1alpha1
            kind: Workflow
            metadata:
              name: alias-string-test
            spec:
              entrypoint: dag
              x-tmpl: &utils-template realcity-utils-template
              templates:
                - name: dag
                  dag:
                    tasks:
                      - name: step
                        templateRef:
                          name: *utils-template
                          template: some-template
            """;

    /**
     * Confirms the bug: a plain Jackson YAML mapper throws when a POJO-type field
     * contains an alias token it cannot instantiate from a string.
     */
    @Test
    void plainMapper_throwsOnPojoAlias() {
        ObjectMapper plain = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        assertThrows(Exception.class, () -> plain.readValue(POJO_ALIAS_YAML, Workflow.class),
                "plain Jackson YAML mapper should throw on a POJO-field alias token");
    }

    /**
     * Confirms that {@link ArgoWorkflowExecutor#yamlMapper()} properly resolves a POJO-field
     * alias — the anchored node {@code {limit: 0}} is deserialized into a {@code RetryStrategy},
     * not dropped to null.
     */
    @Test
    void yamlMapper_resolvesPojosAlias() throws Exception {
        Workflow workflow = ArgoWorkflowExecutor.yamlMapper().readValue(POJO_ALIAS_YAML, Workflow.class);

        Template step = workflow.getSpec().getTemplates().stream()
                .filter(t -> "step".equals(t.getName()))
                .findFirst().orElseThrow();

        assertNotNull(step.getRetryStrategy(),
                "YAML POJO-field alias should resolve to the anchored object, not be dropped");
        assertEquals("0", step.getRetryStrategy().getLimit(),
                "resolved retryStrategy should carry the anchor value limit=0");
    }

    /**
     * Confirms that {@link ArgoWorkflowExecutor#yamlMapper()} properly resolves a String-field
     * alias — the anchor value {@code "realcity-utils-template"} is assigned, not the anchor
     * name {@code "utils-template"}.
     *
     * <p>A plain Jackson YAML mapper silently assigns the anchor <em>name</em> here because
     * a string scalar is always valid; the error only surfaces later as a failed WorkflowTemplate
     * lookup.
     */
    @Test
    void yamlMapper_resolvesStringAlias() throws Exception {
        Workflow workflow = ArgoWorkflowExecutor.yamlMapper().readValue(STRING_ALIAS_YAML, Workflow.class);

        String resolvedName = workflow.getSpec().getTemplates().get(0)
                .getDag().getTasks().get(0)
                .getTemplateRef().getName();

        assertEquals("realcity-utils-template", resolvedName,
                "YAML string alias should resolve to the anchor value, not the anchor name");
    }
}
