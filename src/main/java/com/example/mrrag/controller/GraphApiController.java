package com.example.mrrag.controller;

import com.example.mrrag.model.GraphBuildStats;
import com.example.mrrag.service.AstGraphService.EdgeKind;
import com.example.mrrag.service.AstGraphService.NodeKind;
import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.JavaIndexService;
import com.example.mrrag.service.loader.GitLabSourceLoader;
import com.example.mrrag.service.loader.JavaSourceLoader;
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
 *   "ref"       : "main",          // branch, tag, or commit SHA
 *   "gitToken"  : "glpat-xxx"      // optional; falls back to app.gitlab.token
 * }
 * </pre>
 *
 * <p>{@code ref} accepts a branch name, a tag name, or a <strong>full / short commit SHA</strong>.
 * GitLab4J passes it directly to the Repository Files API, which resolves any ref type.
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class GraphApiController {

    private final JavaIndexService javaIndexService;
    private final GitLabApi        gitLabApi;

    // -----------------------------------------------------------------------
    // Request DTO
    // -----------------------------------------------------------------------

    /**
     * @param projectId numeric GitLab project id (required)
     * @param ref       branch, tag, or commit SHA (required)
     * @param gitToken  optional per-request GitLab PAT (overrides application-level token)
     */
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
    public GraphBuildStats ingestRef(@RequestBody IngestRefRequest request) throws Exception {

        if (request.projectId() == null) {
            throw new IllegalArgumentException("projectId must not be null");
        }
        if (request.ref() == null || request.ref().isBlank()) {
            throw new IllegalArgumentException("ref must not be blank");
        }

        long wallStart = System.currentTimeMillis();

        // Use a per-request GitLabApi instance if a custom token was supplied
        GitLabApi api = resolveApi(request.gitToken());

        JavaSourceLoader loader = new GitLabSourceLoader(api, request.projectId(), request.ref());

        long fetchStart = System.currentTimeMillis();
        ProjectGraph graph = javaIndexService.getGraphFromLoader(loader);
        long buildMs = System.currentTimeMillis() - fetchStart;
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a GitLabApi instance configured with the per-request token when provided,
     * otherwise falls back to the application-level bean.
     */
    private GitLabApi resolveApi(String requestToken) {
        if (requestToken == null || requestToken.isBlank()) {
            return gitLabApi;
        }
        // Create a lightweight client reusing the same host URL
        String hostUrl = gitLabApi.getGitLabServerUrl();
        return new GitLabApi(hostUrl, requestToken);
    }
}
