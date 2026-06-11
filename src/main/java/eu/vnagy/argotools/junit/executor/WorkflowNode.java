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

import eu.vnagy.argotools.junit.model.Template;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public sealed interface WorkflowNode permits DagRun, ItemRun, PodRun, StepsRun, UninitializedNode {

    static WorkflowNode from(String name, Template template,
                             Map<String, Template> templateMap, Set<String> constructing) {
        return from(name, template, templateMap, constructing, null);
    }

    static WorkflowNode from(String name, Template template,
                             Map<String, Template> templateMap, Set<String> constructing,
                             String owningWt) {
        if (template.getDag() != null) {
            return new DagRun(name, template, templateMap, constructing, owningWt);
        }
        if (template.getSteps() != null && !template.getSteps().isEmpty()) {
            return new StepsRun(name, template, templateMap, constructing, owningWt);
        }
        return new PodRun(name, template);
    }

    /** Current (final) attempt's direct child nodes; empty for leaf nodes. */
    default List<WorkflowNode> children() { return List.of(); }
    /** Human-readable error message set when {@link #errored()} is true; empty string otherwise. */
    default String message() {
        return children().stream()
                .map(WorkflowNode::message)
                .filter(m -> !m.isEmpty())
                .findFirst()
                .orElse("");
    }
    /** Total completed attempts (0 = not yet run, 1 = ran once, N = retried N-1 times). */
    default int attempts() { return 0; }
    /** Child-node maps from each failed attempt before the final one, in order. */
    default List<Map<String, WorkflowNode>> attemptHistory() { return List.of(); }

    String name();
    boolean succeeded();
    boolean failed();
    /** At least one iteration succeeded; for non-loop nodes equivalent to {@link #succeeded()}. */
    default boolean anySucceeded() { return succeeded(); }
    /** All iterations failed; for non-loop nodes equivalent to {@link #failed()}. */
    default boolean allFailed()    { return failed(); }
    /** Task's {@code when} condition was false. */
    boolean skipped();
    /** Task's {@code depends} condition was false. */
    boolean omitted();
    /** Infrastructure/execution error (not a non-zero exit code). */
    boolean errored();
    boolean daemoned();
    boolean running();
    boolean pending();
    /** Mark as skipped: {@code when} condition was false. */
    void skip();
    /** Mark as omitted: {@code depends} condition was false. */
    void omit();
    CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx, Map<String, String> inputParams);
}
