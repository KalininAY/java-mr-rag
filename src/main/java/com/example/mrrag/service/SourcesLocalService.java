package com.example.mrrag.service;

import com.example.mrrag.controller.GraphIngestController;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;


@Slf4j
@Service
public class SourcesLocalService implements SourceProvider {
    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    @Override
    public List<String> sourceProvide(String repoUrl, String branch, String gitToken, Boolean forceReClone) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl must not be blank");
        }

        Path cloneDir = resolveCloneDir(repoUrl, branch);
        boolean exists = Files.isDirectory(cloneDir);

        // ── 2. Clone via JGit (skip if already present) ───────────────────
        long cloneMs;

        if (exists)
            log.info("Reusing existing clone: {}", cloneDir);

        try {
            Files.createDirectories(cloneDir);
            long cloneStart = System.currentTimeMillis();
            cloneWithJGit(repoUrl, branch, cloneDir, gitToken);
            cloneMs = System.currentTimeMillis() - cloneStart;
        } catch (Exception e) {
            try {
                FileSystemUtils.deleteRecursively(cloneDir);
            } catch (IOException ex) {
                log.info("Not deleted");
            }
            throw new RuntimeException(e);
        }

        log.info("Cloned {} (branch={}) in {} ms → {}", repoUrl, branch, cloneMs, cloneDir);
        return collectSourceRoots(cloneDir);
    }

    @Override
    public List<String> sourceProvider(Path rootProject) {
        return collectSourceRoots(rootProject);
    }

    /**
     * Directory segments that should never be fed to Spoon as source roots.
     */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "build", "target", "out", ".gradle", ".git",
            "generated", "generated-sources", "generated-test-sources"
    );

    /**
     * Collects only real source directories (src/main/java, src/test/java,
     * or the project root itself), explicitly skipping build/target output
     * directories that contain duplicate .java files and trigger
     * "type already defined" errors in JDT.
     */
    private List<String> collectSourceRoots(Path projectRoot) {
        List<Path> candidates = List.of(
                projectRoot.resolve("src/main/java"),
                projectRoot.resolve("src/test/java")
        );
        List<String> roots = candidates.stream()
                .filter(Files::isDirectory)
                .map(Path::toString)
                .toList();
        if (!roots.isEmpty()) {
            log.debug("Using standard source roots: {}", roots);
            return roots;
        }

        List<String> fallback = new ArrayList<>();
        try (Stream<Path> top = Files.list(projectRoot)) {
            top.filter(Files::isDirectory)
                    .filter(p -> !EXCLUDED_DIRS.contains(p.getFileName().toString()))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> fallback.add(p.toString()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        if (!fallback.isEmpty()) {
            log.debug("Fallback source roots: {}", fallback);
            return fallback;
        }

        return List.of(projectRoot.toString());
    }

    /**
     * Clones a repository using JGit in-process — no external {@code git}
     * binary required, no {@code /dev/tty} access needed.
     *
     * <p>Authentication: GitLab accepts {@code oauth2} as the username with
     * a personal access token (PAT) as the password for HTTPS clones.
     * GitHub accepts {@code oauth2} or any non-empty username with the PAT.
     */
    private void cloneWithJGit(String repoUrl, String branch, Path targetDir, String token)
            throws GitAPIException, IOException {

        var cmd = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir.toFile())
                .setDepth(1)

                .setCloneAllBranches(false);

        if (branch != null && !branch.isBlank()) {
            // JGit expects full ref name for branch
            cmd.setBranch("refs/heads/" + branch);
        }

        if (token != null && !token.isBlank()) {
            // GitLab: username="oauth2", password=<PAT>
            // GitLab also accepts username=<anything>, password=<PAT> for HTTP Basic
            cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("oauth2", token));
        }

        // Forward JGit progress to application log
        cmd.setProgressMonitor(new GraphIngestController.Slf4jProgressMonitor());

        log.info("JGit clone: {} branch={} → {}", repoUrl,
                branch != null ? branch : "<default>", targetDir);

        try (Git git = cmd.call()) {
            log.debug("JGit clone complete, HEAD={}",
                    git.getRepository().resolve("HEAD"));
        }
    }

    private Path resolveCloneDir(String repoUrl, String branch) {
        String repoSlug = repoUrl
                .replaceAll(".*/", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-zA-Z0-9_.-]", "_");
        String branchSlug = branch.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return Path.of(workspaceDir, "repos", repoSlug, branchSlug);
    }


    // -----------------------------------------------------------------------
    // JGit progress → SLF4J bridge
    // -----------------------------------------------------------------------

    /**
     * Bridges JGit {@link org.eclipse.jgit.lib.ProgressMonitor} to SLF4J
     * so clone progress appears in the application log.
     */
    private static final class Slf4jProgressMonitor
            extends org.eclipse.jgit.lib.BatchingProgressMonitor {

        @Override
        protected void onUpdate(String taskName, int workCurr, Duration d) {
            log.debug("[jgit] {} {}", taskName, workCurr);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr, Duration d) {
            log.info("[jgit] {} done ({})", taskName, workCurr);
        }

        @Override
        protected void onUpdate(String taskName, int workCurr, int workTotal, int percentDone, Duration d) {
            log.debug("[jgit] {} {}/{} ({}%)", taskName, workCurr, workTotal, percentDone);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr, int workTotal, int percentDone, Duration d) {
            log.info("[jgit] {} done {}/{}", taskName, workCurr, workTotal);
        }
    }
}
