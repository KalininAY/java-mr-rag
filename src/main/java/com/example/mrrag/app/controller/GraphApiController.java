package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.graph.cache.CachedManagementService;
import com.example.mrrag.graph.markdown.GraphMarkdownRenderer;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@Validated
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Tag(name = "Граф (GitLab API)",
        description = "Построение AST-графа")
public class GraphApiController {

    private static final String MARKDOWN_UTF8 = "text/markdown;charset=UTF-8";

    private final CachedManagementService cachedService;

    @Operation(
            summary = "Построить граф с кэшированием (клон один раз, инкрементальное обновление)",
            description = """
                    Клонирует ветку и строит полный AST-граф.
                    Повторный запрос с тем же namespace/repo/branch: если SHA не изменился —
                    возвращает граф из памяти.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))),
                    @ApiResponse(responseCode = "500", description = "Ошибка клонирования или построения графа")
            }
    )
    @PostMapping(value = "/build",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<GraphBuildStats> buildGraph(@RequestBody @Valid RemoteProjectRequest request) {
        return Mono.fromCallable(() -> {
            ProjectKey key = ProjectKey.from(request);
            ProjectGraph graph = cachedService.getOrBuildGraph(key, request.token());
            return toStats(request, graph);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Operation(
            summary = "Получить ноду графа в Markdown",
            description = """
                    Возвращает карточку узла AST-графа в формате GitHub-Flavored Markdown.
                    Карточка содержит kind, simpleName, filePath, диапазон строк,
                    bodyHash, declarationSnippet и sourceSnippet (если есть).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Markdown-карточка ноды"),
                    @ApiResponse(responseCode = "404", description = "Нода не найдена")
            }
    )
    @GetMapping(value = "/node", produces = MARKDOWN_UTF8)
    public Mono<ResponseEntity<String>> getNode(
            @RequestParam String namespace,
            @RequestParam String repo,
            @RequestParam String branch,
            @RequestParam String nodeId) {
        return Mono.fromCallable(() -> {
            ProjectKey key = new ProjectKey(namespace, repo, branch);
            ProjectGraph graph = cachedService.getOrBuildGraph(key, null);
            GraphNode node = graph.nodes.get(nodeId);
            if (node == null)
                return ResponseEntity.notFound().build();
            return ResponseEntity.ok(GraphMarkdownRenderer.renderNode(node));
        });
    }

    @Operation(
            summary = "Получить рёбра ноды в Markdown",
            description = """
                    Возвращает таблицу рёбер в формате Markdown.
                    
                    **from=true** (по умолчанию) — исходящие рёбра (edgesFrom): вызовы, которые делает нода.
                    **from=false** — входящие рёбра (edgesTo): кто ссылается на данную ноду.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Markdown-таблица рёбер"),
                    @ApiResponse(responseCode = "404", description = "Нода не найдена")
            }
    )
    @GetMapping(value = "/edges", produces = MARKDOWN_UTF8)
    public Mono<ResponseEntity<String>> getEdges(
            @RequestParam String namespace,
            @RequestParam String repo,
            @RequestParam String branch,
            @RequestParam String token,
            @RequestParam String nodeId,
            @RequestParam Boolean from
    ) {
        return Mono.fromCallable(() -> {
            ProjectKey key = new ProjectKey(namespace, repo, branch);
            ProjectGraph graph = cachedService.getOrBuildGraph(key, token);

            if (!graph.nodes.containsKey(nodeId)) {
                return ResponseEntity.<String>notFound().build();
            }

            List<GraphEdge> edges = from
                    ? graph.outgoing(nodeId)
                    : graph.incoming(nodeId);

            String md = GraphMarkdownRenderer.renderEdges(nodeId, edges, from);
            return ResponseEntity.ok(md);
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────

    private GraphBuildStats toStats(RemoteProjectRequest request, ProjectGraph graph) {
        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.nodes.values().stream()
                                .filter(n -> n.kind() == k).count()));
        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()));
        return new GraphBuildStats(
                request.namespace(), request.repo(), request.branch(),
                graph.nodes.size(), edgesByKind.size(),
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }
}
