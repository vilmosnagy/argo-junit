package eu.vnagy.argotools.junit.expressions;

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
 * Shared scenarios for Argo's expression tag templates, {@code {{=<expr>}}}: an
 * <a href="https://expr-lang.org/">expr-lang</a> expression evaluated wherever a simple
 * {@code {{...}}} tag is substituted, over the same variables ({@code inputs.parameters},
 * {@code workflow.*}, {@code tasks} / {@code steps} outputs, {@code item}, ...) expanded into
 * nested maps, plus Argo's helpers ({@code asInt}, {@code toJson}, {@code jsonpath},
 * {@code sprig.*}). A string result is inserted as is, anything else as JSON.
 *
 * <p>Every workflow fails unless the expressions evaluate as described; the checks run inside
 * the pods, so the scenarios need nothing but the outcome. The expected values are the ones the
 * Argo controller produces.
 *
 * <p>Concrete subclasses run the workflows: {@link ExpressionTagTest} through argo-junit,
 * {@link ExpressionTagRealArgoTest} on a real Argo controller, to check that the assertions here
 * match how Argo behaves.
 *
 * @see <a href="https://github.com/vilmosnagy/argo-junit/issues/168">#168</a>
 */
public abstract class ExpressionTagBase {

    /** Runs the workflow in {@code workflowYaml} to completion. */
    protected abstract WorkflowOutcome runWorkflow(String workflowYaml) throws Exception;

    @Test
    void ternaryFallsBackFromInputToWorkflowParameter() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-ternary-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: default-key
                        value: from-workflow
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: given
                            template: pick
                            arguments:
                              parameters:
                                - name: data-key
                                  value: from-input
                                - name: expected
                                  value: from-input
                          - name: empty
                            template: pick
                            arguments:
                              parameters:
                                - name: data-key
                                  value: ""
                                - name: expected
                                  value: from-workflow
                    - name: pick
                      inputs:
                        parameters:
                          - name: data-key
                          - name: expected
                      dag:
                        tasks:
                          - name: check
                            template: check
                            arguments:
                              parameters:
                                - name: key
                                  value: "{{=inputs.parameters['data-key'] != '' ? inputs.parameters['data-key'] : workflow.parameters['default-key']}}"
                                - name: expected
                                  value: "{{inputs.parameters.expected}}"
                    - name: check
                      inputs:
                        parameters:
                          - name: key
                          - name: expected
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.key}}" = "{{inputs.parameters.expected}}"
                """);
        assertTrue(outcome.succeeded(),
                () -> "A ternary expression tag must pick the input parameter, or the workflow parameter when"
                        + " the input is empty" + "\n" + outcome.summary());
    }

    @Test
    void expressionResultsAreFormattedLikeArgo() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-results-test
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: config
                        value: '{"a": {"b": [1, 2.5, "x"]}, "e": [{"n": "A", "v": 1}, {"n": "B", "v": 2}]}'
                  templates:
                    - name: main
                      steps:
                        - - name: check
                            template: check
                            arguments:
                              parameters:
                                - name: n
                                  value: "3"
                                - name: my-param
                                  value: hello
                                - name: empty
                                  value: ""
                    - name: check
                      inputs:
                        parameters:
                          - name: n
                          - name: my-param
                          - name: empty
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - {name: ARITH, value: "{{=asInt(inputs.parameters.n) * 10 + 2 ** 3 - 7 % 3}}"}
                          - {name: DIV, value: "{{=10 / 4}}"}
                          - {name: DIV_WHOLE, value: "{{=10 / 5}}"}
                          - {name: FLOAT, value: "{{=0.1 + 0.2}}"}
                          - {name: BIG, value: "{{=1000000.0 * 1000000}}"}
                          - {name: HUGE, value: "{{=10000000000000000000000.0}}"}
                          - {name: LIST, value: "{{=[1, 'a', true, nil]}}"}
                          - {name: TO_JSON, value: "{{=toJson({'b': 2, 'a': 'x<'}) }}"}
                          - {name: COALESCE, value: "{{=nil ?? 'fallback'}}"}
                          - {name: MEMBERSHIP, value: "{{='b' in ['a', 'b'] && 'c' not in ['a']}}"}
                          - {name: RANGE_FILTER, value: "{{=filter(1..5, # % 2 == 1)}}"}
                          - {name: MAP, value: "{{=map(['a', 'b'], upper(#))}}"}
                          - {name: MAP_FIELD, value: "{{=map([{'a': 1}, {'a': 2}], .a)}}"}
                          - {name: REDUCE, value: "{{=reduce([1, 2, 3], #acc + #, 0)}}"}
                          - {name: SLICE, value: "{{=[1, 2, 3, 4][1:3]}}"}
                          - {name: NEGATIVE_INDEX, value: "{{=[1, 2, 3][-1]}}"}
                          - {name: PIPE, value: "{{=' a ' | trim() | upper()}}"}
                          - {name: STRING, value: "{{=string([1, 'a'])}}"}
                          - {name: TERNARY, value: "{{=len('abc') > 2 ? 'long' : 'short'}}"}
                          - {name: MATCHES, value: "{{=inputs.parameters.n matches '^[0-9]+$'}}"}
                          - {name: DASHED_NAME, value: "{{=inputs.parameters['my-param'] + '!'}}"}
                          - {name: SPRIG_STRINGS, value: "{{=sprig.upper('abc') + sprig.repeat(2, 'x') + sprig.trunc(2, 'abcd')}}"}
                          - {name: SPRIG_MATH, value: "{{=sprig.add(1, 2, 3) / 4}}"}
                          - {name: SPRIG_BASE64, value: "{{=sprig.b64dec(sprig.b64enc('hi'))}}"}
                          - {name: SPRIG_REPLACE, value: "{{=sprig.replace('a', 'b', 'aaa')}}"}
                          - {name: SPRIG_DEFAULT, value: "{{=sprig.default('d', inputs.parameters.empty)}}"}
                          - {name: SPRIG_DICT, value: "{{=sprig.dict('a', sprig.list(1, 'a'))}}"}
                          - {name: JSONPATH_LIST, value: "{{=jsonpath(workflow.parameters.config, '$.a.b')}}"}
                          - {name: JSONPATH_INDEX, value: "{{=jsonpath(workflow.parameters.config, '$.a.b[1]')}}"}
                          - {name: JSONPATH_FILTER, value: "{{=jsonpath(workflow.parameters.config, '$.e[?(@.v > 1)].n')}}"}
                          - {name: JSONPATH_FILTER_AND, value: "{{=jsonpath(workflow.parameters.config, '$.e[?(@.n == \\"A\\" && @.v < 2)].v')}}"}
                          - {name: JSONPATH_WILDCARD, value: "{{=jsonpath(workflow.parameters.config, '$.e[*].n')}}"}
                          - {name: JSONPATH_SLICE, value: "{{=jsonpath(workflow.parameters.config, '$.e[0:1]')}}"}
                        source: |
                          check() {
                            if [ "$2" != "$3" ]; then echo "$1: got '$2', want '$3'"; exit 1; fi
                          }
                          check ARITH "$ARITH" '37'
                          check DIV "$DIV" '2.5'
                          check DIV_WHOLE "$DIV_WHOLE" '2'
                          check FLOAT "$FLOAT" '0.30000000000000004'
                          check BIG "$BIG" '1000000000000'
                          check HUGE "$HUGE" '1e+22'
                          check LIST "$LIST" '[1,"a",true,null]'
                          check TO_JSON "$TO_JSON" '{"a":"x\\u003c","b":2}'
                          check COALESCE "$COALESCE" 'fallback'
                          check MEMBERSHIP "$MEMBERSHIP" 'true'
                          check RANGE_FILTER "$RANGE_FILTER" '[1,3,5]'
                          check MAP "$MAP" '["A","B"]'
                          check MAP_FIELD "$MAP_FIELD" '[1,2]'
                          check REDUCE "$REDUCE" '6'
                          check SLICE "$SLICE" '[2,3]'
                          check NEGATIVE_INDEX "$NEGATIVE_INDEX" '3'
                          check PIPE "$PIPE" 'A'
                          check STRING "$STRING" '[1 a]'
                          check TERNARY "$TERNARY" 'long'
                          check MATCHES "$MATCHES" 'true'
                          check DASHED_NAME "$DASHED_NAME" 'hello!'
                          check SPRIG_STRINGS "$SPRIG_STRINGS" 'ABCxxab'
                          check SPRIG_MATH "$SPRIG_MATH" '1.5'
                          check SPRIG_BASE64 "$SPRIG_BASE64" 'hi'
                          check SPRIG_REPLACE "$SPRIG_REPLACE" 'bbb'
                          check SPRIG_DEFAULT "$SPRIG_DEFAULT" 'd'
                          check SPRIG_DICT "$SPRIG_DICT" '{"a":[1,"a"]}'
                          check JSONPATH_LIST "$JSONPATH_LIST" '[1,2.5,"x"]'
                          check JSONPATH_INDEX "$JSONPATH_INDEX" '2.5'
                          check JSONPATH_FILTER "$JSONPATH_FILTER" '["B"]'
                          check JSONPATH_FILTER_AND "$JSONPATH_FILTER_AND" '[1]'
                          check JSONPATH_WILDCARD "$JSONPATH_WILDCARD" '["A","B"]'
                          check JSONPATH_SLICE "$JSONPATH_SLICE" '[{"n":"A","v":1}]'
                """);
        assertTrue(outcome.succeeded(),
                () -> "Expression tags must evaluate and format their results like Argo" + "\n" + outcome.summary());
    }

    @Test
    void whenConditionWithExpressionTag() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-when-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      steps:
                        - - name: decide
                            template: echo
                            arguments:
                              parameters:
                                - name: message
                                  value: run-it
                        - - name: skipped
                            template: fail
                            when: "{{=steps.decide.outputs.result != 'run-it'}}"
                          - name: ran
                            template: echo
                            when: "{{=steps['decide'].outputs.result == 'run-it'}}"
                            arguments:
                              parameters:
                                - name: message
                                  value: ran
                        - - name: verify
                            template: verify
                            arguments:
                              parameters:
                                - name: ran
                                  value: "{{steps.ran.outputs.result}}"
                    - name: echo
                      inputs:
                        parameters:
                          - name: message
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo "{{inputs.parameters.message}}"
                    - name: fail
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          exit 1
                    - name: verify
                      inputs:
                        parameters:
                          - name: ran
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.ran}}" = "ran"
                """);
        assertTrue(outcome.succeeded(),
                () -> "A when condition with an expression tag must skip or run the step" + "\n" + outcome.summary());
    }

    @Test
    void withParamFromExpressionAndItemInExpression() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-with-param-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: numbers
                            template: check-number
                            arguments:
                              parameters:
                                - name: tens
                                  value: "{{=asInt(item) * 10}}"
                            withParam: "{{=toJson(filter([1, 2, 3], {# > 1}))}}"
                          - name: objects
                            template: check-object
                            arguments:
                              parameters:
                                - name: label
                                  value: "{{=item.name + '=' + item.size}}"
                            withItems:
                              - {name: a, size: "1"}
                              - {name: b, size: "2"}
                    - name: check-number
                      inputs:
                        parameters:
                          - name: tens
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          case "{{inputs.parameters.tens}}" in 20|30) ;; *) exit 1 ;; esac
                    - name: check-object
                      inputs:
                        parameters:
                          - name: label
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          case "{{inputs.parameters.label}}" in a=1|b=2) ;; *) exit 1 ;; esac
                """);
        assertTrue(outcome.succeeded(),
                () -> "withParam from an expression tag must expand to the filtered items, and item must be"
                        + " usable in expression tags" + "\n" + outcome.summary());
    }

    @Test
    void taskOutputsInExpressions() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-task-outputs-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: flip
                            template: flip
                          - name: check
                            depends: flip
                            template: check
                            arguments:
                              parameters:
                                - name: side
                                  value: "{{=tasks.flip.outputs.result == 'heads' ? 'H' : 'T'}}"
                                - name: param
                                  value: "{{=sprig.upper(tasks['flip'].outputs.parameters['coin-side'])}}"
                    - name: flip
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo heads
                          echo -n tails > /tmp/side
                      outputs:
                        parameters:
                          - name: coin-side
                            valueFrom:
                              path: /tmp/side
                    - name: check
                      inputs:
                        parameters:
                          - name: side
                          - name: param
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{inputs.parameters.side}}" = "H"
                          test "{{inputs.parameters.param}}" = "TAILS"
                """);
        assertTrue(outcome.succeeded(),
                () -> "Expression tags must see the outputs of completed tasks" + "\n" + outcome.summary());
    }

    @Test
    void workflowVariablesInExpressions() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-workflow-vars-test
                spec:
                  entrypoint: main
                  onExit: exit-handler
                  arguments:
                    parameters:
                      - name: greeting
                        value: hi
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - {name: NAME, value: "{{=workflow.name}}"}
                          - {name: PARAMS, value: "{{=toJson(workflow.parameters)}}"}
                          - {name: YEAR, value: "{{=workflow.creationTimestamp.Y}}"}
                        source: |
                          test "$NAME" = "{{workflow.name}}"
                          test "$PARAMS" = '{"greeting":"hi","json":"[{\\"name\\":\\"greeting\\",\\"value\\":\\"hi\\"}]"}'
                          test "$YEAR" = "$(date -u +%Y)"
                    - name: exit-handler
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "{{=workflow.status == 'Succeeded' ? 'ok' : workflow.status}}" = "ok"
                """);
        assertTrue(outcome.succeeded(),
                () -> "Expression tags must see the workflow.* variables" + "\n" + outcome.summary());
    }

    @Test
    void fromExpressionCanFallBackToInputArtifact() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-from-expression-input-artifact-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      dag:
                        tasks:
                          - name: pick
                            template: pick
                            arguments:
                              parameters:
                                - name: version
                                  value: ""
                              artifacts:
                                - name: fallback
                                  raw:
                                    data: from fallback
                          - name: consume
                            depends: pick
                            template: consume
                            arguments:
                              artifacts:
                                - name: data
                                  from: "{{tasks.pick.outputs.artifacts.result}}"
                    - name: pick
                      inputs:
                        parameters:
                          - name: version
                        artifacts:
                          - name: fallback
                      outputs:
                        artifacts:
                          - name: result
                            fromExpression: "inputs.parameters['version'] != '' ? tasks['download'].outputs.artifacts['result'] : inputs.artifacts['fallback']"
                      dag:
                        tasks:
                          - name: download
                            when: "'{{inputs.parameters.version}}' != ''"
                            template: download
                    - name: download
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          echo -n downloaded > /tmp/result
                      outputs:
                        artifacts:
                          - name: result
                            path: /tmp/result
                    - name: consume
                      inputs:
                        artifacts:
                          - name: data
                            path: /tmp/data
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        source: |
                          test "$(cat /tmp/data)" = "from fallback"
                """);
        assertTrue(outcome.succeeded(),
                () -> "fromExpression must be able to reference inputs.artifacts" + "\n" + outcome.summary());
    }

    @Test
    void expressionThatFailsAtRuntimeIsLeftInPlace() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-runtime-error-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - {name: UNKNOWN, value: "{{=nope + 1}}"}
                          - {name: TYPE_ERROR, value: "{{='a' + 1}}"}
                        source: |
                          open="$(printf '\\173\\173')"
                          test "$UNKNOWN" = "${open}=nope + 1}}"
                          test "$TYPE_ERROR" = "${open}='a' + 1}}"
                """);
        assertTrue(outcome.succeeded(),
                () -> "An expression tag that fails to evaluate must be left in place, as Argo does" + "\n"
                        + outcome.summary());
    }

    @Test
    void expressionThatFailsToParseFailsTheWorkflow() throws Exception {
        WorkflowOutcome outcome = runWorkflow("""
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-tag-parse-error-test
                spec:
                  entrypoint: main
                  templates:
                    - name: main
                      script:
                        image: alpine:3
                        command: [sh, -e]
                        env:
                          - {name: BROKEN, value: "{{=1 +}}"}
                        source: |
                          true
                """);
        assertFalse(outcome.succeeded(),
                () -> "An expression tag that does not parse must fail the workflow" + "\n" + outcome.summary());
        assertTrue(outcome.summary().contains("failed to evaluate expression"), outcome::summary);
    }
}
