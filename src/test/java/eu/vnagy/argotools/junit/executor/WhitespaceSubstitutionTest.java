package eu.vnagy.argotools.junit.executor;

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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Verifies that substitution patterns tolerate optional whitespace inside {{ }},
 * e.g. {{ workflow.parameters.foo }} and {{workflow.parameters.foo}} are equivalent.
 * Helm chart templates commonly produce the former when escaping Argo expressions.
 */
class WhitespaceSubstitutionTest {

    private static ExecutionContext ctxWithWfParam(String name, String value) {
        return ExecutionContext
                .builder(Map.of(), Map.of(name, value), Executors.newSingleThreadExecutor())
                .build();
    }

    // -------------------------------------------------------------------------
    // workflow.parameters
    // -------------------------------------------------------------------------

    @Test void workflowParam_noSpaces() {
        assertThat(ctxWithWfParam("tag", "v1")
                .substitute("{{workflow.parameters.tag}}", Map.of()), is("v1"));
    }

    @Test void workflowParam_leadingSpace() {
        assertThat(ctxWithWfParam("tag", "v1")
                .substitute("{{ workflow.parameters.tag}}", Map.of()), is("v1"));
    }

    @Test void workflowParam_trailingSpace() {
        assertThat(ctxWithWfParam("tag", "v1")
                .substitute("{{workflow.parameters.tag }}", Map.of()), is("v1"));
    }

    @Test void workflowParam_bothSpaces() {
        assertThat(ctxWithWfParam("tag", "v1")
                .substitute("{{ workflow.parameters.tag }}", Map.of()), is("v1"));
    }

    @Test void workflowParam_embeddedInImageName() {
        assertThat(ctxWithWfParam("tag-tool", "latest")
                .substitute("eu.gcr.io/example/tool:{{ workflow.parameters.tag-tool}}", Map.of()),
                is("eu.gcr.io/example/tool:latest"));
    }

    // -------------------------------------------------------------------------
    // inputs.parameters
    // -------------------------------------------------------------------------

    @Test void inputsParam_noSpaces() {
        ExecutionContext ctx = ctxWithWfParam("unused", "x");
        assertThat(ctx.substitute("{{inputs.parameters.msg}}", Map.of("msg", "hello")), is("hello"));
    }

    @Test void inputsParam_leadingSpace() {
        ExecutionContext ctx = ctxWithWfParam("unused", "x");
        assertThat(ctx.substitute("{{ inputs.parameters.msg}}", Map.of("msg", "hello")), is("hello"));
    }

    @Test void inputsParam_trailingSpace() {
        ExecutionContext ctx = ctxWithWfParam("unused", "x");
        assertThat(ctx.substitute("{{inputs.parameters.msg }}", Map.of("msg", "hello")), is("hello"));
    }

    @Test void inputsParam_bothSpaces() {
        ExecutionContext ctx = ctxWithWfParam("unused", "x");
        assertThat(ctx.substitute("{{ inputs.parameters.msg }}", Map.of("msg", "hello")), is("hello"));
    }
}
