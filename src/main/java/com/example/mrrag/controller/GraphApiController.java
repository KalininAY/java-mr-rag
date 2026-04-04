package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.GraphBuildService;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.source.GitLabProjectSourceProvider;
import com.example.mrrag.service.source.ProjectSourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gitlab4j.api.GitLabApi;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint for <strong>no-clone</strong> graph building via the GitLab API.
 *
 * <pre>
 * POST /api/graph/ingest-ref
 * {
 *   "projectId" : 123,
 *   "ref"       : "main",         // branch, tag, or commit SHA
 *   "gitToken"  : "glpat-xxx"     // optional
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphApiController {

    private final GraphBuildService graphBuildService;
    private final GitLabApi         gitLabApi;

    public record IngestRefRequest(
            Long   projectId,
            String ref,
            String gitToken
    ) {}

    @PostMapping(
            value    = "/ingest-ref",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingestRef(@RequestBody IngestRefRequest request) throws Exception {

        if (request.projectId() == null)
            throw new IllegalArgumentException("projectId must not be null");
        if (request.ref() == null || request.ref().isBlank())
            throw new IllegalArgumentException("ref must not be blank");

        long wallStart = System.currentTimeMillis();

        GitLabApi api = resolveApi(request.gitToken());
        ProjectSourceProvider provider =
                new GitLabProjectSourceProvider(api, request.projectId(), request.ref());

        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphBuildService.buildGraph(provider);
        long buildMs = System.currentTimeMillis() - buildStart;
        long totalMs = System.currentTimeMillis() - wallStart;

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

        String pseudoUrl = "gitlab:" + request.projectId() + "@" + request.ref();
        return new GraphBuildStats(
                pseudoUrl, "(virtual — no clone)",
                0L, buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }

    private GitLabApi resolveApi(String requestToken) {
        if (requestToken == null || requestToken.isBlank()) return gitLabApi;
        return new GitLabApi(gitLabApi.getGitLabServerUrl(), requestToken);
    }
}
