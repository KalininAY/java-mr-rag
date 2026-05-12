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
        description = "Построение AST-графа через GitLab API")
public class GraphApiController {

    private static final String MARKDOWN_UTF8 = "text/markdown;charset=UTF-8";

    private final CachedManagementService cachedService;

    // ──────────────────────────────────────────────────────────────────
    // Build
    // ──────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Построить граф с кэшированием (клон один раз, инкрементальное обновление)",
            description = """
                    При первом запросе клонирует ветку и строит полный AST-граф.
                    Повторный запрос с тем же namespace/repo/branch: если SHA не изменился —
                    возвращает граф из памяти; если появились новые коммиты — перестраивает
                    только изменённые файлы.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))),
                    @ApiResponse(responseCode = "500", description = "Ошибка клонирования или построения графа")
            }
    )
    @PostMapping(value = "/local",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<GraphBuildStats> local(@RequestBody @Valid RemoteProjectRequest request) {
        return Mono.fromCallable(() -> {
            ProjectKey key = ProjectKey.from(request);
            ProjectGraph graph = cachedService.getOrBuildGraph(key, request.token());
            return toStats(request, "(cached clone)", graph);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ──────────────────────────────────────────────────────────────────
    // GET /api/graph/node?nodeId=…
    // Возвращает ноду в виде Markdown
    // ──────────────────────────────────────────────────────────────────

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
            @RequestParam String token,
            @RequestParam String nodeId
    ) {
        return Mono.fromCallable(() -> {
            ProjectKey key = new ProjectKey(namespace, repo, branch);
            ProjectGraph graph = cachedService.getOrBuildGraph(key, token);
            GraphNode node = graph.nodes.get(nodeId);
            if (node == null) {
                return ResponseEntity.<String>notFound().build();
            }
            return ResponseEntity.ok(GraphMarkdownRenderer.renderNode(node));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ──────────────────────────────────────────────────────────────────
    // GET /api/graph/edges?nodeId=…&dir=FROM|TO[&kind=CALLS]
    // Возвращает рёбра ноды в виде Markdown
    // ──────────────────────────────────────────────────────────────────

    @Operation(
            summary = "Получить рёбра ноды в Markdown",
            description = """
                    Возвращает таблицу рёбер в формате GitHub-Flavored Markdown.
                    
                    **dir=FROM** (по умолчанию) — исходящие рёбра (edgesFrom): вызовы, которые делает нода.
                    **dir=TO** — входящие рёбра (edgesTo): кто ссылается на данную ноду.
                    
                    Необязательный параметр **kind** фильтрует по типу ребра
                    (CALLS, DECLARES, USES_TYPE, HAS_JAVADOC и т.д.).
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
            @RequestParam(defaultValue = "FROM") String dir,
            @RequestParam(required = false)       String kind
    ) {
        return Mono.fromCallable(() -> {
            ProjectKey key = new ProjectKey(namespace, repo, branch);
            ProjectGraph graph = cachedService.getOrBuildGraph(key, token);

            if (!graph.nodes.containsKey(nodeId)) {
                return ResponseEntity.<String>notFound().build();
            }

            List<GraphEdge> edges = "TO".equalsIgnoreCase(dir)
                    ? graph.incoming(nodeId)
                    : graph.outgoing(nodeId);

            EdgeKind kindFilter = null;
            if (kind != null && !kind.isBlank()) {
                kindFilter = EdgeKind.valueOf(kind.toUpperCase());
                final EdgeKind fk = kindFilter;
                edges = edges.stream().filter(e -> e.kind() == fk).toList();
            }

            String md = GraphMarkdownRenderer.renderEdges(nodeId, edges, dir, kindFilter);
            return ResponseEntity.ok(md);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────

    private GraphBuildStats toStats(RemoteProjectRequest request, String workspaceDir,
                                    ProjectGraph graph) {
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
                workspaceDir,
                0, 0,
                graph.nodes.size(), 0,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }
}
