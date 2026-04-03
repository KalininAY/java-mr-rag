package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
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
 *   "gitToken" : "glpat-xxxxxxxxxxxx",   // optional; falls back to app.gitlab.token
 *   "force"    : false                   // true = re-clone even if dir exists
 * }
 * </pre>
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

    /** Fallback token from .env / application.yml (app.gitlab.token). */
    @Value("${app.gitlab.token:}")
    private String defaultGitlabToken;

    public GraphIngestController(AstGraphService graphService) {
        this.graphService = graphService;
    }

    // -----------------------------------------------------------------------
    // Request DTO
    // -----------------------------------------------------------------------

    /**
     * @param repoUrl  Git clone URL (HTTPS). SSH is supported too but requires
     *                 an SSH key pre-configured in the runtime environment.
     * @param branch   Branch / tag to checkout. Uses repository default when {@code null}.
     * @param gitToken GitLab personal access token. Falls back to
     *                 {@code app.gitlab.token} from application.yml / .env.
     * @param force    {@code true} = delete existing clone and re-clone.
     */
    public record IngestRequest(
            String  repoUrl,
            String  branch,
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

        // ── 1. Resolve clone directory ────────────────────────────────────
        String branch  = (request.branch() != null && !request.branch().isBlank())
                         ? request.branch() : "default";
        Path cloneDir  = resolveCloneDir(repoUrl, branch);
        boolean exists = Files.isDirectory(cloneDir);

        if (forceReclone && exists) {
            log.info("force=true — deleting existing clone dir: {}", cloneDir);
            FileSystemUtils.deleteRecursively(cloneDir);
            graphService.invalidate(cloneDir);
            exists = false;
        }

        // ── 2. Clone via JGit (skip if already present) ───────────────────
        long cloneMs = 0;
        if (!exists) {
            Files.createDirectories(cloneDir);
            long cloneStart = System.currentTimeMillis();
            try {
                cloneWithJGit(repoUrl, request.branch(), cloneDir,
                              resolveToken(request.gitToken()));
            } catch (Exception e) {
                FileSystemUtils.deleteRecursively(cloneDir);
                throw e;
            }
            cloneMs = System.currentTimeMillis() - cloneStart;
            log.info("Cloned {} (branch={}) in {} ms → {}", repoUrl, branch, cloneMs, cloneDir);
        } else {
            log.info("Reusing existing clone: {}", cloneDir);
        }

        // ── 3. Build (or reuse cached) graph ─────────────────────────────
        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphService.buildGraph(cloneDir);
        long buildMs  = System.currentTimeMillis() - buildStart;
        long totalMs  = System.currentTimeMillis() - wallStart;

        // ── 4. Statistics ───────────────────────────────────────────────
        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.nodes.values().stream()
                                .filter(n -> n.kind() == k).count()
                ));

        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()
                ));

        long totalEdges = graph.edgesFrom.values().stream()
                .mapToLong(java.util.List::size).sum();

        return new GraphBuildStats(
                repoUrl, cloneDir.toString(),
                cloneMs, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size()
        );
    }

    // -----------------------------------------------------------------------
    // JGit clone
    // -----------------------------------------------------------------------

    /**
     * Clones a repository using JGit in-process — no external {@code git}
     * binary required, no {@code /dev/tty} access needed.
     *
     * <p>Authentication: GitLab accepts {@code oauth2} as the username with
     * a personal access token (PAT) as the password for HTTPS clones.
     * GitHub accepts {@code oauth2} or any non-empty username with the PAT.
     */
    private void cloneWithJGit(String repoUrl, String branch, Path targetDir, String token)
            throws GitAPIException {

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
        cmd.setProgressMonitor(new Slf4jProgressMonitor());

        log.info("JGit clone: {} branch={} → {}", repoUrl,
                 branch != null ? branch : "<default>", targetDir);

        try (Git git = cmd.call()) {
            log.debug("JGit clone complete, HEAD={}",
                      git.getRepository().resolve("HEAD"));
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns request token if present, otherwise falls back to app.gitlab.token. */
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
    // JGit progress → SLF4J bridge
    // -----------------------------------------------------------------------

    /**
     * Bridges JGit {@link org.eclipse.jgit.lib.ProgressMonitor} to SLF4J
     * so clone progress appears in the application log.
     */
    private static final class Slf4jProgressMonitor
            extends org.eclipse.jgit.lib.BatchingProgressMonitor {

        @Override
        protected void onUpdate(String taskName, int workCurr) {
            log.debug("[jgit] {} {}", taskName, workCurr);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr) {
            log.info("[jgit] {} done ({})", taskName, workCurr);
        }

        @Override
        protected void onUpdate(String taskName, int workCurr, int workTotal, int percentDone) {
            log.debug("[jgit] {} {}/{} ({}%)", taskName, workCurr, workTotal, percentDone);
        }

        @Override
        protected void onEndTask(String taskName, int workCurr, int workTotal, int percentDone) {
            log.info("[jgit] {} done {}/{}", taskName, workCurr, workTotal);
        }
    }
}
