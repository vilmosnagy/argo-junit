package eu.vnagy.argotools.junit.artifact;

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

import eu.vnagy.argotools.junit.model.Artifact;
import eu.vnagy.argotools.junit.model.GitArtifact;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Clones a git repository on the host and returns the working-tree path so that
 * {@code PodRun} can inject it into the container at the declared artifact path.
 *
 * <p>A shallow clone ({@code --depth 1}) is always used; the full history is not
 * needed for test purposes. {@code revision} is passed as {@code --branch}, which
 * accepts both branch names and tag names. Bare commit SHAs are not supported.
 */
public class GitArtifactDriver implements ArtifactDriver {

    private static final Logger log = LoggerFactory.getLogger(GitArtifactDriver.class);

    @Override
    public boolean supports(Artifact artifact) {
        return artifact.getGit() != null;
    }

    @Override
    public Path download(Artifact artifact, Path tempDir, KubernetesClient k8sClient,
                         String namespace) throws Exception {
        GitArtifact git = artifact.getGit();
        Path dest = Files.createTempDirectory(tempDir, "git-artifact-");

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");
        cmd.add("--depth");
        cmd.add("1");
        if (git.getRevision() != null && !git.getRevision().isBlank()) {
            cmd.add("--branch");
            cmd.add(git.getRevision());
        }
        cmd.add(git.getRepo());
        cmd.add(dest.toString());

        log.debug("Cloning git artifact '{}': {}", artifact.getName(), String.join(" ", cmd));

        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException(
                    "git clone failed (exit " + exitCode + ") for repo '" + git.getRepo()
                            + "' revision '" + git.getRevision() + "':\n" + output.trim());
        }

        return dest;
    }

    @Override
    public void upload(Artifact artifact, Path source, KubernetesClient k8sClient,
                       String namespace) {
        throw new UnsupportedOperationException("git artifacts are read-only");
    }
}
