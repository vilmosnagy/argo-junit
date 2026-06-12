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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Result container for a DAG task that was fanned out via {@code withParam} or {@code withItems}.
 * Each element of {@link #children()} is one iteration's {@link WorkflowNode}.
 * An empty iterations list means the task produced no output — either because its
 * {@code depends} expression was not satisfied ({@link #omitted()} = true) or because
 * its {@code when} condition was false ({@link #skipped()} = true).
 */
public final class ItemRun implements WorkflowNode {

    private final String name;
    private final List<WorkflowNode> iterations;
    private final List<String> itemLabels;
    private final boolean skippedByWhen;

    ItemRun(String name, List<WorkflowNode> iterations, List<String> itemLabels) {
        this(name, iterations, itemLabels, false);
    }

    ItemRun(String name, List<WorkflowNode> iterations, List<String> itemLabels, boolean skippedByWhen) {
        this.name = name;
        this.iterations = List.copyOf(iterations);
        this.itemLabels = List.copyOf(itemLabels);
        this.skippedByWhen = skippedByWhen;
    }

    @Override public String name() { return name; }
    @Override public List<WorkflowNode> children() { return iterations; }
    public List<String> itemLabels() { return itemLabels; }

    @Override public boolean succeeded() {
        return !iterations.isEmpty() && iterations.stream().allMatch(WorkflowNode::succeeded);
    }
    @Override public boolean failed()      { return iterations.stream().anyMatch(WorkflowNode::failed); }
    @Override public boolean anySucceeded(){ return iterations.stream().anyMatch(WorkflowNode::succeeded); }
    @Override public boolean allFailed()   {
        return !iterations.isEmpty() && iterations.stream().allMatch(WorkflowNode::failed);
    }
    @Override public boolean errored()  { return iterations.stream().anyMatch(WorkflowNode::errored); }
    @Override public boolean omitted()  { return iterations.isEmpty() && !skippedByWhen; }
    @Override public boolean skipped()  { return skippedByWhen; }
    @Override public boolean daemoned() { return false; }
    @Override public boolean running()  { return false; }
    @Override public boolean pending()  { return false; }

    @Override public void skip() { throw new UnsupportedOperationException(); }
    @Override public void omit() { throw new UnsupportedOperationException(); }
    @Override public CompletableFuture<WorkflowNode> executeAsync(ExecutionContext ctx,
            Map<String, String> inputParams) {
        throw new UnsupportedOperationException();
    }
}
