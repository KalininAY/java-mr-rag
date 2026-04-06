package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.graph.AstGraphI;
import com.example.mrrag.service.source.GitLabSourcesProvider;
import com.example.mrrag.service.source.SourcesProvider;
import com.example.mrrag.service.dto.ProjectSourceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint for <strong>no-clone</strong> graph building via GitLab API.
 *
 * <pre>
 * POST /api/graph/ingest-ref
 * Content-Type: application/json
 *
 * {
 *   "projectId" : 123,
 *   "ref"       : "main",        // branch, tag, or commit SHA
 *   "gitToken"  : "glpat-xxx"   // optional; falls back to app.gitlab.token
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphApiController {

    private final AstGraphI  graphService;
    private final GitLabApi  gitLabApi;

    // -----------------------------------------------------------------------
    // DTO
    // -----------------------------------------------------------------------

    public record IngestRefRequest(
            Long   projectId,
            String ref,
            String gitToken
    ) {}

    // -----------------------------------------------------------------------
    // Endpoint
    // -----------------------------------------------------------------------

    @PostMapping(
            value    = "/ingest-ref",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingestRef(@RequestBody IngestRefRequest req) throws Exception {

        if (req.projectId() == null)
            throw new IllegalArgumentException("projectId must not be null");
        if (req.ref() == null || req.ref().isBlank())
            throw new IllegalArgumentException("ref must not be blank");

        long wallStart = System.currentTimeMillis();

        GitLabApi api = resolveApi(req.gitToken());
        SourcesProvider provider = new GitLabSourcesProvider(api, req.projectId(), req.ref());

        long fetchStart = System.currentTimeMillis();
        ProjectSourceDto dto = provider.getProjectSourceDto();
        ProjectGraph graph = graphService.buildGraph(dto);
        long buildMs = System.currentTimeMillis() - fetchStart;
        long totalMs = System.currentTimeMillis() - wallStart;

        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.nodes.values().stream().filter(n -> n.kind() == k).count()));
        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()));
        long totalEdges = graph.edgesFrom.values().stream().mapToLong(java.util.List::size).sum();

        return new GraphBuildStats(
                "gitlab:" + req.projectId() + "@" + req.ref(),
                "(virtual — no clone)",
                0L, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private GitLabApi resolveApi(String token) {
        if (token == null || token.isBlank()) return gitLabApi;
        return new GitLabApi(gitLabApi.getGitLabServerUrl(), token);
    }
}
