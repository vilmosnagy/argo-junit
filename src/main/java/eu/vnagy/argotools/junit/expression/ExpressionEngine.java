package eu.vnagy.argotools.junit.expression;

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

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.MapContext;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Evaluates workflow expressions using Apache JEXL3.
 *
 * <p>This is the central expression-language layer described in DESIGN.md
 * ({@code expression/} package). Currently exposes {@code when:} condition
 * evaluation; future methods will cover {@code valueFrom.expression} and
 * {@code fromExpression} with a richer evaluation context (input parameters,
 * task output parameters, task output artifacts).
 *
 * <p>The shared {@link JexlEngine} is thread-safe and reused across all
 * evaluations. {@code BARE_WORD_CONTEXT} is effectively immutable and also shared.
 */
public final class ExpressionEngine {

    private static final JexlEngine JEXL = new JexlBuilder()
            .silent(false)  // surface evaluation errors as exceptions instead of returning null
            .create();

    /**
     * JEXL context that resolves any unknown identifier to its own name.
     *
     * <p>Argo's {@code when:} conditions are substituted before evaluation, so the
     * resulting expression contains only literal values — but those values appear as
     * bare (unquoted) words: e.g. {@code heads == heads} rather than
     * {@code "heads" == "heads"}.  A plain JEXL evaluation in strict mode would fail
     * with "variable 'heads' is undefined".  By making every identifier resolve to its
     * own name we let JEXL compare the strings correctly without any custom pre-parsing.
     */
    private static final JexlContext BARE_WORD_CONTEXT = new JexlContext() {
        @Override public Object get(String name) { return name; }
        @Override public void set(String name, Object value) {}
        @Override public boolean has(String name) { return true; }
    };

    private ExpressionEngine() {}

    /**
     * Evaluates a {@code when:} condition string that has already had its
     * {@code {{...}}} placeholders substituted.
     *
     * <p>Typical inputs after substitution:
     * <ul>
     *   <li>{@code "heads" == "heads"} — string equality</li>
     *   <li>{@code "a" != "b"} — string inequality</li>
     *   <li>{@code true} / {@code false} — boolean literal</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the expression cannot be parsed or evaluated
     */
    public static boolean evaluateWhen(String condition) {
        condition = condition.trim();
        try {
            Object result = JEXL.createExpression(condition).evaluate(BARE_WORD_CONTEXT);
            if (result instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(result));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to evaluate 'when' condition [" + condition + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Evaluates a {@code valueFrom.expression} on a DAG output parameter.
     *
     * <p>The expression may reference:
     * <ul>
     *   <li>{@code inputs.parameters['name']} — the DAG template's own input parameters</li>
     *   <li>{@code tasks['task-name'].outputs.parameters.name} — a child task's output parameter</li>
     * </ul>
     *
     * @return the resolved parameter value, or {@code null} if the expression returns null
     */
    public static String evaluateOutputParamExpression(
            String expression,
            Map<String, String> inputParams,
            Map<String, Map<String, String>> taskOutputParams) {
        JexlContext ctx = buildContext(inputParams, taskOutputParams, null);
        try {
            Object result = JEXL.createExpression(expression).evaluate(ctx);
            return result != null ? String.valueOf(result) : null;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to evaluate output parameter expression [" + expression + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Evaluates a {@code fromExpression} on a DAG output artifact.
     *
     * <p>The expression may reference:
     * <ul>
     *   <li>{@code inputs.parameters['name']} — the DAG template's own input parameters</li>
     *   <li>{@code tasks['task-name'].outputs.artifacts['name']} — a child task's output artifact path</li>
     * </ul>
     *
     * @return the resolved artifact {@link Path}, or {@code null} if the expression returns null
     * @throws IllegalArgumentException if the expression doesn't resolve to a Path
     */
    public static Path evaluateOutputArtifactExpression(
            String expression,
            Map<String, String> inputParams,
            Map<String, Map<String, Path>> taskArtifacts) {
        JexlContext ctx = buildContext(inputParams, null, taskArtifacts);
        try {
            Object result = JEXL.createExpression(expression).evaluate(ctx);
            if (result == null) return null;
            if (result instanceof Path path) return path;
            throw new IllegalArgumentException(
                    "Expression [" + expression + "] did not resolve to a Path; got: "
                    + result.getClass().getName());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to evaluate output artifact expression [" + expression + "]: " + e.getMessage(), e);
        }
    }

    /**
     * Builds a JEXL context with {@code inputs} and {@code tasks} variables for output expressions.
     *
     * <ul>
     *   <li>{@code inputs.parameters} → the given inputParams map</li>
     *   <li>{@code tasks['t'].outputs.parameters} → per-task output param maps (when non-null)</li>
     *   <li>{@code tasks['t'].outputs.artifacts} → per-task output artifact maps (when non-null)</li>
     * </ul>
     */
    private static JexlContext buildContext(
            Map<String, String> inputParams,
            Map<String, Map<String, String>> taskOutputParams,
            Map<String, Map<String, Path>> taskArtifacts) {

        Map<String, Object> inputsMap = new HashMap<>();
        inputsMap.put("parameters", new HashMap<>(inputParams));

        Map<String, Object> tasksMap = new HashMap<>();
        if (taskOutputParams != null) {
            taskOutputParams.forEach((taskName, params) -> {
                Map<String, Object> outputs = new HashMap<>();
                outputs.put("parameters", new HashMap<>(params));
                Map<String, Object> task = new HashMap<>();
                task.put("outputs", outputs);
                tasksMap.put(taskName, task);
            });
        }
        if (taskArtifacts != null) {
            taskArtifacts.forEach((taskName, artifacts) -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> task = (Map<String, Object>) tasksMap.computeIfAbsent(
                        taskName, k -> new HashMap<>());
                @SuppressWarnings("unchecked")
                Map<String, Object> outputs = (Map<String, Object>) task.computeIfAbsent(
                        "outputs", k -> new HashMap<>());
                outputs.put("artifacts", new HashMap<>(artifacts));
            });
        }

        MapContext ctx = new MapContext();
        ctx.set("inputs", inputsMap);
        ctx.set("tasks", tasksMap);
        return ctx;
    }
}
