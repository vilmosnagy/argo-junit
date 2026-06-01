package eu.vnagy.argotools.junit.expression;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;

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
}
