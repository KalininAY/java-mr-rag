package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphProvider;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.model.graph.EdgeKind;
import com.example.mrrag.model.graph.NodeKind;
import com.example.mrrag.model.graph.ProjectGraph;
import com.example.mrrag.service.SourceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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

    private final AstGraphProvider graphService;
    private final SourceProvider sourceProvider;

    /** Fallback token from .env / application.yml (app.gitlab.token). */
    @Value("${app.gitlab.token:}")
    private String defaultGitlabToken;

    public GraphIngestController(AstGraphProvider graphService, SourceProvider sourceProvider) {
        this.graphService = graphService;
        this.sourceProvider = sourceProvider;
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
    public GraphBuildStats ingest(@RequestBody IngestRequest request) {

        long wallStart = System.currentTimeMillis();

        // ── 1. Resolve clone directory ────────────────────────────────────
        String repoUrl = request.repoUrl();
        String branch = (request.branch != null && !request.branch.isBlank()) ? request.branch : "default";
        String gitToken = resolveToken(request.gitToken);
        boolean forceReClone = Boolean.TRUE.equals(request.force());

        List<String> sources = sourceProvider.sourceProvide(repoUrl, branch, gitToken, forceReClone);


        // ── 3. Build (or reuse cached) graph ─────────────────────────────
        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphService.buildGraph(repoUrl + "_" + branch, sources);
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

        GraphBuildStats graphBuildStats = new GraphBuildStats(
                repoUrl, "cloneDir.toString()",
                10, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size()
        );
        return graphBuildStats;
    }


    /** Returns request token if present, otherwise falls back to app.gitlab.token. */
    private String resolveToken(String requestToken) {
        if (requestToken != null && !requestToken.isBlank()) return requestToken;
        if (defaultGitlabToken != null && !defaultGitlabToken.isBlank()) return defaultGitlabToken;
        return null;
    }

}
