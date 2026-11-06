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

/**
 * An expr-lang expression that could not be evaluated. The {@link Kind} decides what happens to
 * the {@code {{=...}}} tag it came from: Argo fails the workflow on a syntax error but leaves the
 * tag in place on an evaluation error; an {@link Kind#UNSUPPORTED} feature is one Argo handles
 * but argo-junit does not, so the run must fail rather than silently diverge from Argo.
 */
final class ExprException extends RuntimeException {

    enum Kind { SYNTAX, EVALUATION, UNSUPPORTED }

    final Kind kind;

    ExprException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    static ExprException syntax(String message, String source, int pos) {
        return new ExprException(Kind.SYNTAX, message + " (1:" + (pos + 1) + ")\n | " + source);
    }

    static ExprException evaluation(String message) {
        return new ExprException(Kind.EVALUATION, message);
    }

    static ExprException unsupported(String feature) {
        return new ExprException(Kind.UNSUPPORTED, feature + " is not supported by argo-junit");
    }
}
