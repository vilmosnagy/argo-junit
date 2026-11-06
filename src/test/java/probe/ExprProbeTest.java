package probe;

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

import eu.vnagy.argotools.junit.testutil.KindArgoCluster;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Disabled("Manual probe: records how real Argo evaluates expr-lang expressions; reads exprs.txt from a local scratch dir")
class ExprProbeTest {

    static final Path OUT = Path.of("/tmp/claude-1000/-home-vilmosnagy-Projects-vnagy-eu-argo-junit/68edb712-0f77-48ab-bea5-5a5e9cce1ba1/scratchpad/probe");

    @Test
    void probe() throws Exception {
        Files.createDirectories(OUT);
        List<String> exprs = Files.readAllLines(OUT.resolve("exprs.txt"));
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < exprs.size(); i++) {
            String e = exprs.get(i);
            if (e.isBlank()) continue;
            params.append("          - name: p").append(i).append("\n            value: '")
                    .append(e.replace("'", "''")).append("'\n");
        }
        String wf = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  name: expr-probe
                spec:
                  entrypoint: main
                  arguments:
                    parameters:
                      - name: my-param
                        value: hello
                      - name: json
                        value: '[{"value": "true"}]'
                  templates:
                    - name: main
                      inputs:
                        parameters:
                %s
                      container:
                        image: alpine:3
                        command: ["true"]
                """.formatted(params.toString().indent(4).stripTrailing());
        Files.writeString(OUT.resolve("wf.yaml"), wf);
        var outcome = KindArgoCluster.shared().run(wf, Duration.ofMinutes(5));
        Files.writeString(OUT.resolve("result.yaml"), outcome.succeeded() + "\n" + outcome.summary());
    }

    @Test
    void probeWorkflows() throws Exception {
        Files.createDirectories(OUT);
        var dir = OUT.resolve(System.getProperty("probeDir", "wfs"));
        List<Path> files;
        try (var s = Files.list(dir)) { files = s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList(); }
        for (Path f : files) {
            String wf = Files.readString(f);
            var outcome = KindArgoCluster.shared().run(wf, Duration.ofMinutes(5));
            Files.writeString(dir.resolve(f.getFileName() + ".result"), outcome.succeeded() + "\n" + outcome.summary());
        }
    }
}
