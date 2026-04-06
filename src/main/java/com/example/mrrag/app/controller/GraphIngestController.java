package com.example.mrrag.app.controller;

import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.graph.AstGraphService;
import com.example.mrrag.graph.GraphRawBuilder.EdgeKind;
import com.example.mrrag.graph.GraphRawBuilder.NodeKind;
import com.example.mrrag.graph.GraphRawBuilder.ProjectGraphRaw;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BatchingProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint that clones a Git repository into a <b>persistent</b>
 * workspace directory using <b>JGit</b> (no external {@code git} process),
 * builds its AST symbol graph via {@link AstGraphService} and returns
 * build statistics.
 *
 * <pre>
 * POST /api/graph/ingest
 * Content-Type: application/json
 *
 * {
 *   "repoUrl"  : "http://gitlab.example.com/org/repo.git",
 *   "branch"   : "feature/my-branch",
 *   "commit"   : "a1b2c3d4",            // optional full / short commit SHA to checkout
 *   "gitToken" : "glpat-xxxxxxxxxxxx", // optional; falls back to app.gitlab.token
 *   "force"    : false                 // true = re-clone even if dir exists
 * }
 * </pre>
 *
 * <p>When {@code commit} is provided the clone checks out that exact commit after cloning
 * the specified branch (or default branch if {@code branch} is omitted).  This is useful
 * for analysing the repository state at a specific point in time without waiting for a full
 * re-clone.
 *
 * <p>Clone directory layout:
 * <pre>
 *   ${app.workspace.dir}/repos/&lt;repo-slug&gt;/&lt;branch-slug&gt;
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
public class GraphIngestController {

    private final AstGraphService graphService;

    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    @Value("${app.gitlab.token:}")
    private String defaultGitlabToken;

    public GraphIngestController(AstGraphService graphService) {
        this.graphService = graphService;
    }

    // -----------------------------------------------------------------------
    // Request DTO
    // -----------------------------------------------------------------------

    /**
     * @param repoUrl  Git clone URL (HTTPS).
     * @param branch   Branch / tag to clone. Uses repository default when {@code null}.
     * @param commit   Optional commit SHA to checkout after cloning.  Accepts full (40-char)
     *                 or abbreviated (7+ char) SHA.  Takes precedence over {@code branch} for
     *                 the working-tree state but the branch is still used as the clone ref.
     * @param gitToken GitLab PAT. Falls back to {@code app.gitlab.token}.
     * @param force    {@code true} = delete existing clone and re-clone.
     */
    public record IngestRequest(
            String  repoUrl,
            String  branch,
            String  commit,
            String  gitToken,
            Boolean force
    ) {}

    // -----------------------------------------------------------------------
    // Endpoint
    // -----------------------------------------------------------------------

    @PostMapping(
            value    = "/ingest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingest(@RequestBody IngestRequest request) throws Exception {

        long wallStart = System.currentTimeMillis();

        String repoUrl = request.repoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl must not be blank");
        }

        boolean forceReclone = Boolean.TRUE.equals(request.force());
        String branch  = (request.branch() != null && !request.branch().isBlank())
                         ? request.branch() : "default";
        String commit  = request.commit();

        Path cloneDir  = resolveCloneDir(repoUrl, branch);
        boolean exists = Files.isDirectory(cloneDir);

        if (forceReclone && exists) {
            log.info("force=true — deleting existing clone dir: {}", cloneDir);
            FileSystemUtils.deleteRecursively(cloneDir);
            graphService.invalidate(cloneDir);
            exists = false;
        }

        long cloneMs = 0;
        if (!exists) {
            Files.createDirectories(cloneDir);
            long cloneStart = System.currentTimeMillis();
            try {
                cloneWithJGit(repoUrl, request.branch(), commit, cloneDir,
                              resolveToken(request.gitToken()));
            } catch (Exception e) {
                FileSystemUtils.deleteRecursively(cloneDir);
                throw e;
            }
            cloneMs = System.currentTimeMillis() - cloneStart;
            log.info("Cloned {} (branch={}, commit={}) in {} ms → {}",
                    repoUrl, branch, commit != null ? commit : "HEAD", cloneMs, cloneDir);
        } else {
            log.info("Reusing existing clone: {}", cloneDir);
        }

        long buildStart = System.currentTimeMillis();
        ProjectGraphRaw graph = graphService.buildGraph(cloneDir);
        long buildMs  = System.currentTimeMillis() - buildStart;
        long totalMs  = System.currentTimeMillis() - wallStart;

        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.nodes.values().stream()
                                .filter(n -> n.kind() == k).count()));

        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()));

        long totalEdges = graph.edgesFrom.values().stream()
                .mapToLong(java.util.List::size).sum();

        return new GraphBuildStats(
                repoUrl, cloneDir.toString(),
                cloneMs, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }

    // -----------------------------------------------------------------------
    // JGit clone + optional checkout
    // -----------------------------------------------------------------------

    /**
     * Clones the repository and, if {@code commitSha} is non-blank, performs a
     * detached-HEAD checkout to that exact commit after the clone completes.
     */
    private void cloneWithJGit(String repoUrl, String branch, String commitSha,
                               Path targetDir, String token)
            throws GitAPIException, IOException {

        var cmd = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(targetDir.toFile())
                .setDepth(1)
                .setCloneAllBranches(false);

        if (branch != null && !branch.isBlank()) {
            cmd.setBranch("refs/heads/" + branch);
        }

        if (token != null && !token.isBlank()) {
            cmd.setCredentialsProvider(
                    new UsernamePasswordCredentialsProvider("oauth2", token));
        }

        cmd.setProgressMonitor(new Slf4jProgressMonitor());

        log.info("JGit clone: {} branch={} → {}", repoUrl,
                branch != null ? branch : "<default>", targetDir);

        try (Git git = cmd.call()) {
            if (commitSha != null && !commitSha.isBlank()) {
                log.info("Checking out commit {}", commitSha);
                git.checkout()
                   .setName(commitSha)
                   .call();
                log.info("Checked out commit {}", commitSha);
            }
            log.debug("JGit done, HEAD={}", git.getRepository().resolve("HEAD"));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String resolveToken(String requestToken) {
        if (requestToken != null && !requestToken.isBlank()) return requestToken;
        if (defaultGitlabToken != null && !defaultGitlabToken.isBlank()) return defaultGitlabToken;
        return null;
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
    // JGit progress → SLF4J
    // -----------------------------------------------------------------------

    private static final class Slf4jProgressMonitor extends BatchingProgressMonitor {

        @Override
        protected void onUpdate(String taskName, int workCurr, Duration d) {
            log.debug("[jgit] {} {}", taskName, workCurr);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr, Duration d) {
            log.info("[jgit] {} done ({})", taskName, workCurr);
        }

        @Override
        protected void onUpdate(String taskName, int workCurr, int workTotal,
                                int percentDone, Duration d) {
            log.debug("[jgit] {} {}/{} ({}%)", taskName, workCurr, workTotal, percentDone);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr, int workTotal,
                                 int percentDone, Duration d) {
            log.info("[jgit] {} done {}/{}", taskName, workCurr, workTotal);
        }
    }
}
