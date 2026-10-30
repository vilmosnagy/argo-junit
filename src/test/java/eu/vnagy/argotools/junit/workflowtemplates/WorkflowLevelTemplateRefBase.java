package eu.vnagy.argotools.junit.workflowtemplates;

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

import eu.vnagy.argotools.junit.testutil.WorkflowOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared scenarios for a Workflow that takes its spec from a WorkflowTemplate through
 * {@code spec.workflowTemplateRef}. Argo joins the two specs ({@code JoinWorkflowSpec}): the
 * Workflow's fields win, unset ones fall back to the WorkflowTemplate's, and lists such as
 * {@code arguments.parameters} are merged element by element on their {@code name}. A Workflow
 * that references a WorkflowTemplate may not define {@code templates} of its own.
 *
 * <p>Every workflow fails unless the fields arrive merged as described; the checks run inside
 * the pods, so the scenarios need nothing but the outcome. A spec Argo rejects shows up as a
 * failed outcome whose summary holds Argo's validation message.
 *
 * <p>Concrete subclasses run the workflows: {@link WorkflowLevelTemplateRefTest} through
 * argo-junit, {@link WorkflowLevelTemplateRefRealArgoTest} on a real Argo controller, to check
 * that the assertions here match how Argo behaves.
 *
 * @see <a href="https://github.com/vilmosnagy/argo-junit/issues/181">#181</a>
 */
public abstract class WorkflowLevelTemplateRefBase {

    /**
     * Creates (or replaces) the WorkflowTemplate or ClusterWorkflowTemplate in
     * {@code templateYaml}, in the namespace the workflows run in.
     */
    protected abstract void applyTemplate(String templateYaml) throws Exception;

    /** Runs the workflow in {@code workflowYaml} to completion. */
    protected abstract WorkflowOutcome runWorkflow(String workflowYaml) throws Exception;

    @Test
    void refOnlyWorkflowRunsTemplateSpec() throws Exception {
        applyTemplate("""
                apiVersion: argoproj.io/v1alpha1
                kind: WorkflowTemplate
                metadata:
                  name: wftref-ref-only
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: message
                        value: from-template
                  templates:
                    - name: main
                      inputs:
                        parameters:
                          - name: message
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.message}}" = "from-template"
                """);

        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: wftref-ref-only-test
                spec:
                  workflowTemplateRef:
                    name: wftref-ref-only
                """);
        assertTrue(outcome.succeeded(),
                () -> "A ref-only Workflow must run the WorkflowTemplate's entrypoint with its arguments"
                        + "\n" + outcome.summary());
    }

    @Test
    void workflowArgumentsOverrideOnlyTheParametersTheyName() throws Exception {
        applyTemplate("""
                apiVersion: argoproj.io/v1alpha1
                kind: WorkflowTemplate
                metadata:
                  name: wftref-partial-args
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: kept
                        value: template-kept
                      - name: overridden
                        value: template-overridden
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{workflow.parameters.kept}}" = "template-kept"
                          test "{{workflow.parameters.overridden}}" = "workflow-overridden"
                """);

        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: wftref-partial-args-test
                spec:
                  arguments:
                    parameters:
                      - name: overridden
                        value: workflow-overridden
                  workflowTemplateRef:
                    name: wftref-partial-args
                """);
        assertTrue(outcome.succeeded(),
                () -> "Workflow arguments must override only the parameters they name; the rest keep"
                        + " the WorkflowTemplate's defaults" + "\n" + outcome.summary());
    }

    @Test
    void workflowEntrypointOverridesTemplateEntrypoint() throws Exception {
        applyTemplate("""
                apiVersion: argoproj.io/v1alpha1
                kind: WorkflowTemplate
                metadata:
                  name: wftref-entrypoint
                spec:
                  entrypoint: fail
                  templates:
                    - name: fail
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          exit 1
                    - name: pass
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          true
                """);

        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: wftref-entrypoint-test
                spec:
                  entrypoint: pass
                  workflowTemplateRef:
                    name: wftref-entrypoint
                """);
        assertTrue(outcome.succeeded(),
                () -> "The Workflow's entrypoint must win over the WorkflowTemplate's" + "\n" + outcome.summary());
    }

    @Test
    void workflowWithOwnTemplatesIsRejected() throws Exception {
        applyTemplate("""
                apiVersion: argoproj.io/v1alpha1
                kind: WorkflowTemplate
                metadata:
                  name: wftref-own-templates
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          true
                """);

        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: wftref-own-templates-test
                spec:
                  workflowTemplateRef:
                    name: wftref-own-templates
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          true
                """);
        assertFalse(outcome.succeeded(),
                () -> "A Workflow with a workflowTemplateRef must not define templates" + "\n" + outcome.summary());
        // Argo's message: "Templates is invalid field in spec if workflow referred WorkflowTemplate
        // reference". Only its start is matched: the status YAML wraps long lines.
        assertTrue(outcome.summary().contains("Templates is invalid field in spec"), outcome::summary);
    }

    @Test
    void clusterScopedRefRunsClusterWorkflowTemplate() throws Exception {
        applyTemplate("""
                apiVersion: argoproj.io/v1alpha1
                kind: ClusterWorkflowTemplate
                metadata:
                  name: wftref-cluster-scoped
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: message
                        value: from-cluster-template
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{workflow.parameters.message}}" = "from-cluster-template"
                """);

        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: wftref-cluster-scoped-test
                spec:
                  workflowTemplateRef:
                    name: wftref-cluster-scoped
                    clusterScope: true
                """);
        assertTrue(outcome.succeeded(),
                () -> "clusterScope: true must resolve a ClusterWorkflowTemplate" + "\n" + outcome.summary());
    }
}
