package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.source.GitLabRemoteSourceProvider;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.cache.CachedManagementService;
import com.example.mrrag.graph.model.EdgeKind;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
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

    private final GraphBuilder            graphService;
    private final CodeRepositoryGateway   gatewayRepo;
    private final CachedManagementService cachedService;

    @Operation(
            summary = "Построить граф по revision (без клонирования)",
            description = """
                    Загружает исходные файлы через GitLab Repository Files API и строит AST-граф.
                    Клонирование не выполняется, кэш не используется.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))),
                    @ApiResponse(responseCode = "500", description = "Ошибка при обращении к GitLab API")
            }
    )
    @PostMapping(value = "/remote",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<GraphBuildStats> remote(@RequestBody @Valid RemoteProjectRequest request) {
        return Mono.fromCallable(() -> {
            ProjectSourceProvider provider = new GitLabRemoteSourceProvider(gatewayRepo, request);
            ProjectGraph graph = graphService.buildGraph(provider);
            return toStats(request, "(virtual — no clone)", graph);
        }).subscribeOn(Schedulers.boundedElastic());
    }

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
