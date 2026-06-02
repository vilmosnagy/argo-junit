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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.vnagy.argotools.junit.model.Template;
import eu.vnagy.argotools.junit.model.Workflow;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CoinflipParsingTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void parsesCoinflip() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/examples/coinflip.yaml")) {
            Workflow workflow = YAML.readValue(in, Workflow.class);

            assertThat(workflow.getSpec().getEntrypoint(), is("coinflip"));

            List<Template> templates = workflow.getSpec().getTemplates();
            assertThat(templates, hasSize(4));

            Template coinflip = templates.get(0);
            assertThat(coinflip.getName(), is("coinflip"));
            assertThat(coinflip.getSteps(), hasSize(2));
            assertThat(coinflip.getSteps().get(1), hasSize(2)); // heads + tails branch

            Template flipCoin = templates.get(1);
            assertThat(flipCoin.getName(), is("flip-coin"));
            assertThat(flipCoin.getScript(), notNullValue());
            assertThat(flipCoin.getScript().getImage(), is("python:alpine3.23"));
            assertThat(flipCoin.getScript().getCommand(), contains("python"));

            Template heads = templates.get(2);
            assertThat(heads.getName(), is("heads"));
            assertThat(heads.getContainer(), notNullValue());

            Template tails = templates.get(3);
            assertThat(tails.getName(), is("tails"));
            assertThat(tails.getContainer(), notNullValue());
        }
    }
}
