package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.*;
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
 *   "gitToken" : "glpat-xxxxxxxxxxxx",   // optional; falls back to app.gitlab.token
 *   "force"    : false                   // true = re-clone even if dir exists
 * }
 * </pre>
 *
 * <p>Clone directory layout:
 * <pre>
 *   ${app.workspace.dir}/repos/&lt;project&gt;_&lt;branch&gt;_&lt;shortHash&gt;
 * </pre>
 * Example: {@code repos/my-service_feature-login_a1b2c3d}
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
     * @param repoUrl  Git clone URL (HTTPS).
     * @param branch   Branch / tag to checkout. Uses repository default when {@code null}.
     * @param gitToken GitLab/GitHub PAT. Falls back to {@code app.gitlab.token}.
     * @param force    {@code true} = delete existing clone dir and re-clone.
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
        String  branch       = normBranch(request.branch());

        // ── 1. Temp dir for clone (final name known only after HEAD is read) ──
        String  repoSlug  = extractRepoSlug(repoUrl);
        String  branchSlug = slugify(branch);
        Path    tempDir   = Path.of(workspaceDir, "repos", repoSlug + "_" + branchSlug + "_pending");

        // Check if a finalized dir already exists for this repo+branch
        // (we scan for dirs matching <repoSlug>_<branchSlug>_<7-char-hex>)
        Path existingDir = findExistingCloneDir(repoSlug, branchSlug);

        if (forceReclone && existingDir != null) {
            log.info("force=true — deleting existing clone dir: {}", existingDir);
            FileSystemUtils.deleteRecursively(existingDir);
            graphService.invalidate(existingDir);
            existingDir = null;
        }

        // ── 2. Clone via JGit (skip if already present) ──────────────────────
        long cloneMs = 0;
        Path cloneDir;

        if (existingDir != null) {
            cloneDir = existingDir;
            log.info("Reusing existing clone: {}", cloneDir);
        } else {
            // Clean up any leftover pending dir
            FileSystemUtils.deleteRecursively(tempDir);
            Files.createDirectories(tempDir);

            long cloneStart = System.currentTimeMillis();
            try {
                String shortHash = cloneWithJGit(repoUrl, request.branch(), tempDir,
                                                  resolveToken(request.gitToken()));
                // Rename to final human-readable name now that we have the hash
                cloneDir = Path.of(workspaceDir, "repos",
                                   repoSlug + "_" + branchSlug + "_" + shortHash);
                Files.move(tempDir, cloneDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                FileSystemUtils.deleteRecursively(tempDir);
                throw e;
            }
            cloneMs = System.currentTimeMillis() - cloneStart;
            log.info("Cloned {} (branch={}) in {} ms → {}", repoUrl, branch, cloneMs, cloneDir);
        }

        // ── 3. Build (or reuse cached) graph ─────────────────────────────────
        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphService.buildGraph(cloneDir);
        long buildMs  = System.currentTimeMillis() - buildStart;
        long totalMs  = System.currentTimeMillis() - wallStart;

        // ── 4. Statistics ─────────────────────────────────────────────────────
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
    // JGit clone — returns 7-char short hash of HEAD
    // -----------------------------------------------------------------------

    /**
     * Clones the repository and returns the 7-character abbreviated HEAD commit SHA.
     * GitLab/GitHub: username {@code oauth2} + PAT works for both.
     */
    private String cloneWithJGit(String repoUrl, String branch, Path targetDir, String token)
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
            ObjectId head = git.getRepository().resolve("HEAD");
            String shortHash = head != null
                    ? head.abbreviate(7).name()
                    : "0000000";
            log.debug("JGit clone complete, HEAD={}", shortHash);
            return shortHash;
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

    /** {@code feature/my-branch} → {@code feature-my-branch} */
    private static String slugify(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    /** {@code https://gitlab.example.com/org/my-service.git} → {@code my-service} */
    private static String extractRepoSlug(String repoUrl) {
        return repoUrl
                .replaceAll(".*/", "")
                .replaceAll("\\.git$", "")
                .replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private static String normBranch(String branch) {
        return (branch != null && !branch.isBlank()) ? branch : "default";
    }

    /**
     * Scans repos/ for an existing directory matching
     * {@code <repoSlug>_<branchSlug>_<7-char-hex>}.
     */
    private Path findExistingCloneDir(String repoSlug, String branchSlug) throws IOException {
        Path reposDir = Path.of(workspaceDir, "repos");
        if (!Files.isDirectory(reposDir)) return null;
        String prefix = repoSlug + "_" + branchSlug + "_";
        try (var stream = Files.list(reposDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        if (!name.startsWith(prefix)) return false;
                        String suffix = name.substring(prefix.length());
                        // 7 hex chars
                        return suffix.matches("[0-9a-f]{7}");
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    // -----------------------------------------------------------------------
    // JGit progress → SLF4J bridge
    // -----------------------------------------------------------------------

    private static final class Slf4jProgressMonitor
            extends org.eclipse.jgit.lib.BatchingProgressMonitor {

        @Override protected void onUpdate(String t, int c, Duration d) { log.debug("[jgit] {} {}", t, c); }
        @Override protected void onEndTask(String t, int c, Duration d) { log.info("[jgit] {} done ({})", t, c); }
        @Override protected void onUpdate(String t, int c, int total, int pct, Duration d) { log.debug("[jgit] {} {}/{} ({}%)", t, c, total, pct); }
        @Override protected void onEndTask(String t, int c, int total, int pct, Duration d) { log.info("[jgit] {} done {}/{}", t, c, total); }
    }
}
