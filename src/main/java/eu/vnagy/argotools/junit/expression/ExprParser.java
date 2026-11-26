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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses an <a href="https://expr-lang.org/docs/language-definition">expr-lang</a> expression,
 * the language of Argo's {@code {{=...}}} tags, into a {@link Node} tree.
 *
 * <p>A Pratt parser with expr-lang's operator precedences. Covers literals (numbers with
 * {@code _} separators and hex/octal/binary prefixes, the three string quotes, arrays, maps),
 * member and index access (with {@code ?.}), slices, calls, the unary and binary operators,
 * {@code ? :}, {@code ??}, ranges ({@code ..}), pipes ({@code |}) and the closures of the
 * predicate builtins ({@code filter(list, {# > 1})}, {@code map(list, .field)}).
 */
final class ExprParser {

    /** A parsed expression. */
    sealed interface Node {
        record Literal(Object value) implements Node {}
        record Identifier(String name) implements Node {}
        /** {@code #}, {@code #acc} or {@code #index} inside a closure; {@code name} is "" for {@code #}. */
        record Pointer(String name) implements Node {}
        record Member(Node target, Node property, boolean optional) implements Node {}
        record Slice(Node target, Node from, Node to) implements Node {}
        record Call(Node callee, List<Node> args) implements Node {}
        record Closure(Node body) implements Node {}
        record Unary(String op, Node operand) implements Node {}
        record Binary(String op, Node left, Node right) implements Node {}
        record Conditional(Node condition, Node then, Node otherwise) implements Node {}
        record ArrayLiteral(List<Node> items) implements Node {}
        record MapLiteral(List<Node> keys, List<Node> values) implements Node {}
    }

    // expr-lang's parser/operator/operator.go
    private static final Map<String, Integer> BINARY = Map.ofEntries(
            Map.entry("or", 10), Map.entry("||", 10),
            Map.entry("and", 15), Map.entry("&&", 15),
            Map.entry("==", 20), Map.entry("!=", 20), Map.entry("<", 20), Map.entry(">", 20),
            Map.entry("<=", 20), Map.entry(">=", 20), Map.entry("in", 20), Map.entry("matches", 20),
            Map.entry("contains", 20), Map.entry("startsWith", 20), Map.entry("endsWith", 20),
            Map.entry("..", 25),
            Map.entry("+", 30), Map.entry("-", 30),
            Map.entry("*", 60), Map.entry("/", 60), Map.entry("%", 60),
            Map.entry("**", 100), Map.entry("^", 100),
            Map.entry("??", 500));
    private static final Set<String> RIGHT_ASSOCIATIVE = Set.of("**", "^");
    private static final Set<String> NEGATABLE = Set.of("in", "matches", "contains", "startsWith", "endsWith");
    private static final int UNARY_NOT = 50;
    private static final int UNARY_SIGN = 90;

    /** Builtins whose second argument is a closure. */
    static final Set<String> PREDICATE_BUILTINS = Set.of(
            "all", "none", "any", "one", "filter", "map", "count", "find", "findIndex", "findLast",
            "findLastIndex", "groupBy", "sortBy", "sum", "reduce");

    private static final List<String> OPERATORS = List.of(
            "**", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "..",
            "+", "-", "*", "/", "%", "^", "!", "<", ">", "?", ":", ".", ",", "|", "(", ")", "[", "]", "{", "}");

    private enum Kind { NUMBER, STRING, IDENTIFIER, POINTER, OPERATOR, EOF }

    private record Token(Kind kind, String text, Object value, int pos) {
        boolean is(String operator) {
            return (kind == Kind.OPERATOR || kind == Kind.IDENTIFIER) && text.equals(operator);
        }
    }

    private final String source;
    private final List<Token> tokens;
    private int current;

    private ExprParser(String source) {
        this.source = source;
        this.tokens = tokenize(source);
    }

    /** Parses {@code source}, failing with an {@link ExprException.Kind#SYNTAX} error. */
    static Node parse(String source) {
        ExprParser parser = new ExprParser(source);
        Node node = parser.parseExpression();
        Token trailing = parser.peek();
        if (trailing.kind != Kind.EOF) throw parser.unexpected(trailing);
        return node;
    }

    // -------------------------------------------------------------------------
    // Grammar

    private Node parseExpression() {
        Node node = parseConditional();
        while (peek().is("|")) {
            next();
            Token name = expect(Kind.IDENTIFIER);
            expectOperator("(");
            List<Node> args = new ArrayList<>();
            args.add(node);
            args.addAll(parseArguments(name.text, 1));
            node = new Node.Call(new Node.Identifier(name.text), args);
        }
        return node;
    }

    private Node parseConditional() {
        Node condition = parseBinary(0);
        if (!peek().is("?")) return condition;
        next();
        if (peek().is(":")) {  // a ?: b
            next();
            return new Node.Binary("?:", condition, parseConditional());
        }
        Node then = parseConditional();
        expectOperator(":");
        return new Node.Conditional(condition, then, parseConditional());
    }

    private Node parseBinary(int minPrecedence) {
        Node left = parseUnary();
        while (true) {
            Token token = peek();
            boolean negated = token.is("not") && NEGATABLE.contains(peek(1).text) && peek(1).kind == Kind.IDENTIFIER;
            String op = negated ? peek(1).text : token.text;
            Integer precedence = token.kind == Kind.OPERATOR || token.kind == Kind.IDENTIFIER ? BINARY.get(op) : null;
            if (precedence == null || precedence < minPrecedence) return left;
            next();
            if (negated) next();
            Node right = parseBinary(RIGHT_ASSOCIATIVE.contains(op) ? precedence : precedence + 1);
            Node binary = new Node.Binary(normalize(op), left, right);
            left = negated ? new Node.Unary("!", binary) : binary;
        }
    }

    private static String normalize(String op) {
        return switch (op) {
            case "or" -> "||";
            case "and" -> "&&";
            case "^" -> "**";
            default -> op;
        };
    }

    private Node parseUnary() {
        Token token = peek();
        if (token.is("!") || token.is("not")) {
            next();
            return new Node.Unary("!", parseBinary(UNARY_NOT));
        }
        if (token.is("-") || token.is("+")) {
            next();
            return new Node.Unary(token.text, parseBinary(UNARY_SIGN));
        }
        return parsePostfix(parsePrimary());
    }

    private Node parsePrimary() {
        Token token = next();
        switch (token.kind) {
            case NUMBER, STRING:
                return new Node.Literal(token.value);
            case POINTER:
                return new Node.Pointer(token.text);
            case IDENTIFIER:
                switch (token.text) {
                    case "true": return new Node.Literal(Boolean.TRUE);
                    case "false": return new Node.Literal(Boolean.FALSE);
                    case "nil": return new Node.Literal(null);
                    default:
                        if (peek().is("(")) {
                            next();
                            return new Node.Call(new Node.Identifier(token.text), parseArguments(token.text, 0));
                        }
                        return new Node.Identifier(token.text);
                }
            case OPERATOR:
                switch (token.text) {
                    case "(": {
                        Node inner = parseExpression();
                        expectOperator(")");
                        return inner;
                    }
                    case "[": {
                        List<Node> items = new ArrayList<>();
                        while (!peek().is("]")) {
                            items.add(parseExpression());
                            if (!peek().is("]")) expectOperator(",");
                        }
                        next();
                        return new Node.ArrayLiteral(items);
                    }
                    case "{":
                        return parseMapLiteral();
                    case ".": {  // .field inside a closure: a member of #
                        Token name = expect(Kind.IDENTIFIER);
                        return new Node.Member(new Node.Pointer(""), new Node.Literal(name.text), false);
                    }
                    default:
                        throw unexpected(token);
                }
            default:
                throw unexpected(token);
        }
    }

    private Node parseMapLiteral() {
        List<Node> keys = new ArrayList<>();
        List<Node> values = new ArrayList<>();
        while (!peek().is("}")) {
            Token key = next();
            switch (key.kind) {
                case IDENTIFIER -> keys.add(new Node.Literal(key.text));
                case STRING, NUMBER -> keys.add(new Node.Literal(key.value));
                default -> {
                    if (!key.is("(")) throw unexpected(key);
                    keys.add(parseExpression());
                    expectOperator(")");
                }
            }
            expectOperator(":");
            values.add(parseExpression());
            if (!peek().is("}")) expectOperator(",");
        }
        next();
        return new Node.MapLiteral(keys, values);
    }

    private Node parsePostfix(Node node) {
        while (true) {
            Token token = peek();
            if (token.is(".") || token.is("?.")) {
                next();
                boolean optional = token.is("?.");
                if (optional && peek().is("[")) {
                    next();
                    node = parseIndex(node, true);
                    continue;
                }
                Token name = expect(Kind.IDENTIFIER);
                node = new Node.Member(node, new Node.Literal(name.text), optional);
                if (peek().is("(")) {
                    next();
                    node = new Node.Call(node, parseArguments(null, 0));
                }
            } else if (token.is("[")) {
                next();
                node = parseIndex(node, false);
            } else if (token.is("(")) {
                next();
                node = new Node.Call(node, parseArguments(null, 0));
            } else {
                return node;
            }
        }
    }

    private Node parseIndex(Node target, boolean optional) {
        if (peek().is(":")) {
            next();
            Node to = peek().is("]") ? null : parseExpression();
            expectOperator("]");
            return new Node.Slice(target, null, to);
        }
        Node index = parseExpression();
        if (peek().is(":")) {
            next();
            Node to = peek().is("]") ? null : parseExpression();
            expectOperator("]");
            return new Node.Slice(target, index, to);
        }
        expectOperator("]");
        return new Node.Member(target, index, optional);
    }

    /**
     * Parses call arguments after the opening parenthesis. For a predicate builtin the argument
     * at position 1 (counting the {@code offset} arguments a pipe supplies) is a closure, written
     * with or without braces.
     */
    private List<Node> parseArguments(String function, int offset) {
        List<Node> args = new ArrayList<>();
        while (!peek().is(")")) {
            boolean closure = function != null && PREDICATE_BUILTINS.contains(function) && args.size() + offset == 1;
            if (closure && peek().is("{")) {
                next();
                Node body = parseExpression();
                expectOperator("}");
                args.add(new Node.Closure(body));
            } else if (closure) {
                args.add(new Node.Closure(parseExpression()));
            } else {
                args.add(parseExpression());
            }
            if (!peek().is(")")) expectOperator(",");
        }
        next();
        return args;
    }

    private Token peek() {
        return peek(0);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(current + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token token = peek();
        if (current < tokens.size() - 1) current++;
        return token;
    }

    private Token expect(Kind kind) {
        Token token = next();
        if (token.kind != kind) throw unexpected(token);
        return token;
    }

    private void expectOperator(String operator) {
        Token token = next();
        if (!token.is(operator) || token.kind != Kind.OPERATOR) throw unexpected(token);
    }

    private ExprException unexpected(Token token) {
        String description = switch (token.kind) {
            case EOF -> "EOF";
            case OPERATOR -> "Operator(\"" + token.text + "\")";
            case IDENTIFIER -> "Identifier(\"" + token.text + "\")";
            case POINTER -> "Pointer(\"#" + token.text + "\")";
            default -> token.kind.name().charAt(0) + token.kind.name().substring(1).toLowerCase()
                    + "(\"" + token.text + "\")";
        };
        return ExprException.syntax("unexpected token " + description, source, token.pos);
    }

    // -------------------------------------------------------------------------
    // Lexer

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < source.length()
                    && Character.isDigit(source.charAt(i + 1)) && !lastIsValue(tokens))) {
                i = readNumber(source, i, tokens);
            } else if (c == '\'' || c == '"') {
                i = readString(source, i, c, tokens);
            } else if (c == '`') {
                int end = source.indexOf('`', i + 1);
                if (end < 0) throw ExprException.syntax("literal not terminated", source, i);
                String text = source.substring(i + 1, end);
                tokens.add(new Token(Kind.STRING, text, text, i));
                i = end + 1;
            } else if (c == '#') {
                int end = i + 1;
                while (end < source.length() && isIdentifierPart(source.charAt(end))) end++;
                tokens.add(new Token(Kind.POINTER, source.substring(i + 1, end), null, i));
                i = end;
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int end = i + 1;
                while (end < source.length() && isIdentifierPart(source.charAt(end))) end++;
                tokens.add(new Token(Kind.IDENTIFIER, source.substring(i, end), null, i));
                i = end;
            } else {
                String operator = null;
                for (String candidate : OPERATORS) {
                    if (source.startsWith(candidate, i)) {
                        operator = candidate;
                        break;
                    }
                }
                if (operator == null) throw ExprException.syntax("unrecognized character: U+%04X '%c'"
                        .formatted((int) c, c), source, i);
                tokens.add(new Token(Kind.OPERATOR, operator, null, i));
                i += operator.length();
            }
        }
        tokens.add(new Token(Kind.EOF, "", null, source.length()));
        return tokens;
    }

    private static boolean lastIsValue(List<Token> tokens) {
        if (tokens.isEmpty()) return false;
        Token last = tokens.getLast();
        return last.kind != Kind.OPERATOR || last.text.equals(")") || last.text.equals("]");
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int readNumber(String source, int start, List<Token> tokens) {
        int i = start;
        if (source.startsWith("0x", i) || source.startsWith("0X", i)
                || source.startsWith("0o", i) || source.startsWith("0O", i)
                || source.startsWith("0b", i) || source.startsWith("0B", i)) {
            int radix = switch (Character.toLowerCase(source.charAt(i + 1))) {
                case 'x' -> 16;
                case 'o' -> 8;
                default -> 2;
            };
            i += 2;
            int digitsStart = i;
            while (i < source.length() && (Character.digit(source.charAt(i), radix) >= 0 || source.charAt(i) == '_')) i++;
            String digits = source.substring(digitsStart, i).replace("_", "");
            tokens.add(new Token(Kind.NUMBER, source.substring(start, i), Long.parseLong(digits, radix), start));
            return i;
        }
        boolean isFloat = false;
        while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
        // "1..3" is a range, not the float "1." followed by ".3"
        if (i < source.length() && source.charAt(i) == '.' && !source.startsWith("..", i)) {
            isFloat = true;
            i++;
            while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
        }
        if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
            int exponent = i + 1;
            if (exponent < source.length() && (source.charAt(exponent) == '+' || source.charAt(exponent) == '-')) exponent++;
            if (exponent < source.length() && Character.isDigit(source.charAt(exponent))) {
                isFloat = true;
                i = exponent;
                while (i < source.length() && Character.isDigit(source.charAt(i))) i++;
            }
        }
        String text = source.substring(start, i);
        String digits = text.replace("_", "");
        Object value;
        try {
            value = isFloat ? (Object) Double.parseDouble(digits) : (Object) Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw ExprException.syntax("invalid number " + text, source, start);
        }
        tokens.add(new Token(Kind.NUMBER, text, value, start));
        return i;
    }

    private static int readString(String source, int start, char quote, List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (true) {
            if (i >= source.length()) throw ExprException.syntax("literal not terminated", source, start);
            char c = source.charAt(i);
            if (c == quote) break;
            if (c == '\\' && i + 1 < source.length()) {
                char escaped = source.charAt(++i);
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'v' -> sb.append('\u000B');
                    case 'a' -> sb.append('\u0007');
                    case '0' -> sb.append('\0');
                    case 'x' -> {
                        sb.append((char) Integer.parseInt(source.substring(i + 1, i + 3), 16));
                        i += 2;
                    }
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(source.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> sb.append(escaped);
                }
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        tokens.add(new Token(Kind.STRING, source.substring(start, i + 1), sb.toString(), start));
        return i + 1;
    }
}
