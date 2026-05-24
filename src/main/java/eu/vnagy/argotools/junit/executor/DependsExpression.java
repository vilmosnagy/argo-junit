package eu.vnagy.argotools.junit.executor;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed representation of an Argo enhanced depends expression.
 *
 * <p>Construct one per DAG task from its raw {@code depends} string. Task names are
 * extracted once at construction time. Call {@link #taskNames()} to get the dependency
 * set and {@link #evaluate(Map)} to decide whether the task should run.
 *
 * <p>Syntax: task names joined by {@code &&}, {@code ||}, optionally with {@code !} and
 * parentheses. A bare task name means the task must have succeeded. A qualified name
 * ({@code Task.Succeeded}, {@code Task.Failed}, {@code Task.Skipped}) tests a specific
 * terminal state.
 *
 * <p>Examples:
 * <pre>
 *   A && B
 *   A || B
 *   A && (C.Succeeded || C.Failed)
 *   should-execute-2.Succeeded || should-not-execute
 * </pre>
 */
public final class DependsExpression {

    private static final Pattern TOKEN =
            Pattern.compile("([A-Za-z0-9][A-Za-z0-9_-]*)(?:\\.(Succeeded|Failed|Skipped))?");

    private static final Set<String> STATUS_QUALIFIERS = Set.of("Succeeded", "Failed", "Skipped");

    private final String raw;
    private final Set<String> names;

    public DependsExpression(String depends) {
        this.raw   = (depends == null || depends.isBlank()) ? "" : depends;
        this.names = parseNames(this.raw);
    }

    /**
     * All task names referenced in the expression (status qualifiers stripped).
     * Empty set when the expression is blank (no dependencies).
     */
    public Set<String> taskNames() {
        return names;
    }

    /**
     * Evaluates whether a task should run given the completed state of its dependencies.
     *
     * @return {@code true} if the task should run, {@code false} if it should be skipped.
     *         Always {@code true} when the expression is blank.
     */
    public boolean evaluate(Map<String, WorkflowNode> completed) {
        if (raw.isEmpty()) return true;

        Matcher m = TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name      = m.group(1);
            String qualifier = m.group(2);
            WorkflowNode node = completed.get(name);
            boolean val;
            if (qualifier != null) {
                val = node != null && switch (qualifier) {
                    case "Succeeded" -> node.succeeded();
                    case "Failed"    -> node.failed();
                    case "Skipped"   -> node.skipped();
                    default          -> false;
                };
            } else {
                val = node != null && node.succeeded();
            }
            m.appendReplacement(sb, val ? "true" : "false");
        }
        m.appendTail(sb);

        return new BoolParser(sb.toString()).parseOr();
    }

    private static Set<String> parseNames(String depends) {
        if (depends.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(depends);
        while (m.find()) {
            String name = m.group(1);
            result.add(name);
        }
        return result;
    }

    // Recursive-descent parser for boolean expressions with &&, ||, !, ()
    private static final class BoolParser {
        private final String s;
        private int pos;

        BoolParser(String s) {
            this.s = s.replaceAll("\\s+", " ").trim();
        }

        boolean parseOr() {
            boolean result = parseAnd();
            while (pos < s.length()) {
                skipSpaces();
                if (pos + 1 < s.length() && s.charAt(pos) == '|' && s.charAt(pos + 1) == '|') {
                    pos += 2;
                    result |= parseAnd();
                } else {
                    break;
                }
            }
            return result;
        }

        boolean parseAnd() {
            boolean result = parseFactor();
            while (pos < s.length()) {
                skipSpaces();
                if (pos + 1 < s.length() && s.charAt(pos) == '&' && s.charAt(pos + 1) == '&') {
                    pos += 2;
                    result &= parseFactor();
                } else {
                    break;
                }
            }
            return result;
        }

        boolean parseFactor() {
            skipSpaces();
            if (pos < s.length() && s.charAt(pos) == '!') {
                pos++;
                return !parseFactor();
            }
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                boolean result = parseOr();
                skipSpaces();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return result;
            }
            if (s.startsWith("true", pos))  { pos += 4; return true; }
            if (s.startsWith("false", pos)) { pos += 5; return false; }
            throw new IllegalArgumentException("Unexpected token at position " + pos + " in: " + s);
        }

        void skipSpaces() {
            while (pos < s.length() && s.charAt(pos) == ' ') pos++;
        }
    }
}
