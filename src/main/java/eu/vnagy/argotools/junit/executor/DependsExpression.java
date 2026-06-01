package eu.vnagy.argotools.junit.executor;

import java.util.LinkedHashSet;
import java.util.List;
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
 * parentheses. A bare task name is equivalent to {@code (task.Succeeded || task.Skipped || task.Daemoned)}.
 * A qualified name tests a specific terminal state: {@code Succeeded}, {@code Failed},
 * {@code Errored}, {@code Skipped}, {@code Omitted}, {@code Daemoned},
 * {@code AnySucceeded}, {@code AllFailed}.
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
            Pattern.compile("([A-Za-z0-9][A-Za-z0-9_-]*)(?:\\.(Succeeded|Failed|Errored|Skipped|Omitted|Daemoned|AnySucceeded|AllFailed))?");

    private static final Set<String> STATUS_QUALIFIERS =
            Set.of("Succeeded", "Failed", "Errored", "Skipped", "Omitted", "Daemoned", "AnySucceeded", "AllFailed");

    private final String raw;
    private final Set<String> names;

    public DependsExpression(String depends) {
        this.raw   = (depends == null || depends.isBlank()) ? "" : depends;
        this.names = parseNames(this.raw);
    }

    /**
     * Constructs a {@code DependsExpression} from either the {@code depends:} expression string
     * or the {@code dependencies:} list, whichever is present.
     *
     * <p>When {@code dependencies:} is used (the simple list form), each name is implicitly
     * a bare-name dependency (equivalent to {@code A && B} in the expression form).
     */
    public static DependsExpression from(String depends, List<String> dependencies) {
        if (depends != null && !depends.isBlank()) return new DependsExpression(depends);
        if (dependencies != null && !dependencies.isEmpty()) {
            return new DependsExpression(String.join(" && ", dependencies));
        }
        return new DependsExpression(null);
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
                    case "Succeeded"    -> node.succeeded();
                    case "Failed"       -> node.failed();
                    case "Errored"      -> node.errored();
                    case "Skipped"      -> node.skipped();
                    case "Omitted"      -> node.omitted();
                    case "Daemoned"     -> node.daemoned();
                    case "AnySucceeded" -> node.succeeded();   // withItems not supported; equivalent for single tasks
                    case "AllFailed"    -> node.failed();      // withItems not supported; equivalent for single tasks
                    default             -> false;
                };
            } else {
                // bare name: equivalent to (task.Succeeded || task.Skipped || task.Daemoned)
                val = node != null && (node.succeeded() || node.skipped() || node.daemoned());
            }
            m.appendReplacement(sb, Boolean.toString(val));
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
