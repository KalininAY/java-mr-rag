package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint that clones a Git repository, builds its AST symbol graph
 * via {@link AstGraphService} and returns build statistics.
 *
 * <pre>
 * POST /api/graph/ingest
 * Content-Type: application/json
 *
 * { "repoUrl": "https://github.com/org/repo.git", "branch": "main" }
 * </pre>
 *
 * The cloned directory is removed after the graph is built (or on error).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/graph")
public class GraphIngestController {

    private final AstGraphService graphService;

    // -----------------------------------------------------------------------
    // Request DTO
    // -----------------------------------------------------------------------

    /**
     * @param repoUrl Git clone URL (HTTPS or SSH).
     * @param branch  Optional branch / tag / commit to checkout after clone.
     *                When {@code null} the default branch is used.
     */
    public record IngestRequest(String repoUrl, String branch) {}

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

        // ── 1. Clone ──────────────────────────────────────────────────────
        Path cloneDir = Files.createTempDirectory("mrrag-clone-");
        long cloneStart = System.currentTimeMillis();
        try {
            cloneRepo(repoUrl, request.branch(), cloneDir);
        } catch (Exception e) {
            cleanup(cloneDir);
            throw e;
        }
        long cloneMs = System.currentTimeMillis() - cloneStart;
        log.info("Cloned {} in {} ms → {}", repoUrl, cloneMs, cloneDir);

        // ── 2. Build graph ────────────────────────────────────────────────
        long buildStart = System.currentTimeMillis();
        ProjectGraph graph;
        try {
            graph = graphService.buildGraph(cloneDir);
        } finally {
            cleanup(cloneDir);
        }
        long buildMs = System.currentTimeMillis() - buildStart;
        long totalMs = System.currentTimeMillis() - wallStart;

        // ── 3. Compute statistics ─────────────────────────────────────────
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

    private void cloneRepo(String repoUrl, String branch, Path targetDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        if (branch != null && !branch.isBlank()) {
            pb.command("git", "clone", "--depth", "1", "--branch", branch, repoUrl, targetDir.toString());
        } else {
            pb.command("git", "clone", "--depth", "1", repoUrl, targetDir.toString());
        }
        pb.redirectErrorStream(true);
        pb.inheritIO();

        log.info("Running: {}", String.join(" ", pb.command()));
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git clone failed with exit code " + exitCode + " for URL: " + repoUrl);
        }
    }

    private void cleanup(Path dir) {
        try {
            FileSystemUtils.deleteRecursively(dir);
            log.debug("Deleted temp clone dir: {}", dir);
        } catch (IOException e) {
            log.warn("Failed to delete temp clone dir {}: {}", dir, e.getMessage());
        }
    }
}
