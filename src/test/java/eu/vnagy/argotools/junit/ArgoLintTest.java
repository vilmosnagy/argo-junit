package eu.vnagy.argotools.junit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Validates all custom workflow fixtures with {@code argo lint --offline}.
 *
 * <p>Requires the {@code argo} CLI on PATH. To skip the entire class:
 * <pre>mvn test -DskipArgoLint=true</pre>
 */
@DisabledIfSystemProperty(named = "skipArgoLint", matches = "true")
class ArgoLintTest {

    private static String argoBin;

    @BeforeAll
    static void findArgo() {
        for (String candidate : new String[]{"argo", "/usr/local/bin/argo"}) {
            try {
                int code = new ProcessBuilder(candidate, "version", "--short")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor();
                if (code == 0) {
                    argoBin = candidate;
                    return;
                }
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException(
                "argo CLI not found on PATH. Install it or skip with: mvn test -DskipArgoLint=true");
    }

    /** A template with both container and script set is rejected — they are mutually exclusive. */
    @Test
    void invalidWorkflowFailsLint() throws Exception {
        String yaml = """
                apiVersion: argoproj.io/v1alpha1
                kind: Workflow
                metadata:
                  generateName: invalid-
                spec:
                  entrypoint: main
                  templates:
                  - name: main
                    container:
                      image: alpine
                      command: [echo, hello]
                    script:
                      image: alpine
                      command: [sh]
                      source: echo hello
                """;

        Path tmp = Files.createTempFile("invalid-workflow-", ".yaml");
        try {
            Files.writeString(tmp, yaml);
            LintResult result = lint(List.of(tmp));
            assertThat("expected lint to reject a template with both container and script",
                    result.code(), not(0));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Every custom fixture under {@code src/test/resources/} (the {@code examples/} submodule
     * symlink is excluded) must pass {@code argo lint --offline}.
     *
     * <p>Files are linted per directory so that workflows referencing sibling WorkflowTemplates
     * can be resolved offline — {@code argo lint --offline} resolves cross-references only from
     * the files passed as arguments.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("customWorkflowGroups")
    void customWorkflowPassesLint(String directory, List<Path> files) throws Exception {
        LintResult result = lint(files);
        assertThat("argo lint --offline failed:\n" + result.output(), result.code(), is(0));
    }

    static Stream<Arguments> customWorkflowGroups() throws IOException {
        Path root = Path.of("src/test/resources");
        Path examples = root.resolve("examples");
        return Files.walk(root)
                .filter(p -> p.toString().endsWith(".yaml"))
                .filter(p -> !p.startsWith(examples))
                .collect(Collectors.groupingBy(Path::getParent))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Arguments.of(e.getKey().toString(), e.getValue()));
    }

    private record LintResult(int code, String output) {}

    private LintResult lint(List<Path> files) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(argoBin);
        cmd.add("lint");
        cmd.add("--offline");
        files.stream().map(Path::toString).forEach(cmd::add);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        return new LintResult(p.waitFor(), output);
    }
}
