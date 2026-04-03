package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint that clones a Git repository into a <b>persistent</b>
 * workspace directory, builds its AST symbol graph via {@link AstGraphService}
 * and returns build statistics.
 *
 * <pre>
 * POST /api/graph/ingest
 * Content-Type: application/json
 *
 * {
 *   "repoUrl"  : "https://gitlab.example.com/org/repo.git",
 *   "branch"   : "main",
 *   "gitToken" : "glpat-xxxxxxxxxxxx",   // optional, embedded into clone URL
 *   "force"    : false                   // true = re-clone even if dir exists
 * }
 * </pre>
 *
 * <p>The clone is kept on disk so that subsequent calls with the same
 * {@code repoUrl + branch} are served from cache without re-cloning.
 * Pass {@code "force": true} to force a fresh clone (e.g. after a force-push).
 *
 * <p>Clone directory layout:
 * <pre>
 *   ${app.workspace.dir}/repos/&lt;repo-slug&gt;/&lt;branch&gt;
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
public class GraphIngestController {

    private final AstGraphService graphService;

    /** Base workspace directory — set via {@code WORKSPACE_DIR} env-var or application.yml. */
    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    public GraphIngestController(AstGraphService graphService) {
        this.graphService = graphService;
    }

    // -----------------------------------------------------------------------
    // Request DTO
    // -----------------------------------------------------------------------

    /**
     * @param repoUrl  Git clone URL (HTTPS or SSH).
     * @param branch   Optional branch / tag to checkout. Default branch when {@code null}.
     * @param gitToken Optional personal access token. Embedded as
     *                 {@code https://<token>@host/path.git} — never logged.
     * @param force    When {@code true}: delete existing clone dir and re-clone.
     *                 Useful after force-pushes. Default: {@code false}.
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
        String branch   = (request.branch() != null && !request.branch().isBlank())
                          ? request.branch() : "default";
        Path cloneDir   = resolveCloneDir(repoUrl, branch);
        boolean exists  = Files.isDirectory(cloneDir);

        if (forceReclone && exists) {
            log.info("force=true — deleting existing clone dir: {}", cloneDir);
            FileSystemUtils.deleteRecursively(cloneDir);
            graphService.invalidate(cloneDir);
            exists = false;
        }

        // ── 2. Clone (skip if already present) ───────────────────────────
        long cloneMs = 0;
        if (!exists) {
            Files.createDirectories(cloneDir);
            long cloneStart = System.currentTimeMillis();
            try {
                String cloneUrl = injectToken(repoUrl, request.gitToken());
                cloneRepo(cloneUrl, request.branch(), cloneDir);
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

        // ── 4. Compute statistics ─────────────────────────────────────────
        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.nodes.values().stream()
                                .filter(n -> n.kind() == k)
                                .count()
                ));

        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(
                        k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k)
                                .count()
                ));

        long totalEdges = graph.edgesFrom.values().stream()
                .mapToLong(java.util.List::size)
                .sum();

        return new GraphBuildStats(
                repoUrl,
                cloneDir.toString(),
                cloneMs,
                buildMs,
                totalMs,
                graph.nodes.size(),
                totalEdges,
                nodesByKind,
                edgesByKind,
                graph.byFile.size()
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a stable, filesystem-safe path for a given repo + branch:
     * {@code <workspaceDir>/repos/<repo-slug>/<branch-slug>}
     */
    private Path resolveCloneDir(String repoUrl, String branch) {
        // derive a short slug from the repo URL: last path segment without .git
        String repoSlug = repoUrl
                .replaceAll(".*/", "")     // keep last segment
                .replaceAll("\\.git$", "")  // strip .git
                .replaceAll("[^a-zA-Z0-9_.-]", "_"); // make FS-safe

        String branchSlug = branch.replaceAll("[^a-zA-Z0-9_.-]", "_");

        return Path.of(workspaceDir, "repos", repoSlug, branchSlug);
    }

    /**
     * Embeds a personal access token into an HTTPS URL:
     * {@code https://host/path} → {@code https://<token>@host/path}.
     * SSH URLs are returned unchanged.
     * Returns the original URL when {@code token} is blank.
     */
    private String injectToken(String repoUrl, String token) {
        if (token == null || token.isBlank()) return repoUrl;
        if (!repoUrl.startsWith("http")) return repoUrl; // SSH — no injection needed
        try {
            URI uri = URI.create(repoUrl);
            String userInfo = token; // GitLab: token alone works; GitHub: "oauth2:<token>"
            URI authed = new URI(
                    uri.getScheme(), userInfo,
                    uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment()
            );
            return authed.toString();
        } catch (Exception e) {
            log.warn("Failed to inject token into URL — using plain URL");
            return repoUrl;
        }
    }

    private void cloneRepo(String repoUrl, String branch, Path targetDir)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        if (branch != null && !branch.isBlank()) {
            pb.command("git", "clone", "--depth", "1",
                       "--branch", branch, repoUrl, targetDir.toString());
        } else {
            pb.command("git", "clone", "--depth", "1",
                       repoUrl, targetDir.toString());
        }
        pb.redirectErrorStream(true);
        pb.inheritIO();

        // Mask token in log: replace the userinfo part with ***
        String displayUrl = repoUrl.replaceAll("(https?://)([^@]+@)", "$1***@");
        log.info("Running: git clone --depth 1 {} {} {}",
                 branch != null ? "--branch " + branch : "",
                 displayUrl, targetDir);

        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(
                    "git clone failed (exit " + exitCode + ") for: " + displayUrl);
        }
    }
}
