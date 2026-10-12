package eu.vnagy.argotools.junit.testutil;

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
 * The result of a workflow run that both argo-junit and a real Argo cluster ({@link KindArgoCluster})
 * can report, so the same assertions can be checked against both.
 *
 * @param succeeded whether the workflow finished in the {@code Succeeded} phase
 * @param summary   a human-readable description of the run, for assertion messages
 */
public record WorkflowOutcome(boolean succeeded, String summary) {
}
