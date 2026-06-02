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
import eu.vnagy.argotools.junit.executor.WorkflowRun;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

class ArgumentsFromConfigmapTest {

    @Test
    void argumentsParametersFromConfigmap() throws Exception {
        var executor = ArgoWorkflowExecutor.from(
                Path.of(getClass().getResource("/examples/arguments-parameters-from-configmap.yaml").toURI()));

        executor.getKubernetesClient()
                .configMaps()
                .inNamespace("default")
                .resource(new ConfigMapBuilder()
                        .withNewMetadata()
                            .withName("simple-parameters")
                        .endMetadata()
                        .addToData("msg", "hello world")
                        .build())
                .create();

        WorkflowRun run = executor.execute();
        assertThat(run.succeeded(), is(true));
        assertThat(run.entrypoint(), is(instanceOf(PodRun.class)));
        assertThat(((PodRun) run.entrypoint()).logs().strip(), is("hello world"));
    }
}
