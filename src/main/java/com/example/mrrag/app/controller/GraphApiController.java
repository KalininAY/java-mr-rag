package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.source.GitLabLocalSourceProvider;
import com.example.mrrag.app.source.GitLabRemoteSourceProvider;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.cache.CachedSourceManagementService;
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoints for graph building.
 *
 * <ul>
 *   <li>{@code POST /api/graph/remote}  — no-clone, reads files via GitLab API</li>
 *   <li>{@code POST /api/graph/local}   — clones on every call, no caching</li>
 *   <li>{@code POST /api/graph/cached}  — clone once, incremental patch on new commits</li>
 *   <li>{@code POST /api/graph/sync}    — explicit pull + incremental patch trigger</li>
 * </ul>
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Tag(name = "Граф (GitLab API)", description = "Построение AST-графа напрямую через GitLab API без клонирования репозитория")
public class GraphApiController {

    private final GraphBuilder                graphService;
    private final CodeRepositoryGateway       gatewayRepo;
    private final CachedSourceManagementService cachedService;

    // ------------------------------------------------------------------
    // /remote
    // ------------------------------------------------------------------

    @Operation(
            summary = "Построить граф по revision (без клонирования)",
            description = """
                    Загружает исходные файлы Java-проекта через GitLab Repository Files API и строит
                    AST-граф символов (узлы + рёбра). Клонирование не выполняется — файлы читаются
                    напрямую из GitLab.
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
    public GraphBuildStats remote(@RequestBody @Valid RemoteProjectRequest request) {
        ProjectSourceProvider provider = new GitLabRemoteSourceProvider(gatewayRepo, request);
        ProjectGraph graph = graphService.buildGraph(provider);
        return toStats(request, "(virtual — no clone)", graph);
    }

    // ------------------------------------------------------------------
    // /local
    // ------------------------------------------------------------------

    @Operation(
            summary = "Клонировать репозиторий и построить граф",
            description = """
                    Клонирует Git-репозиторий и строит AST-граф Java-проекта.
                    Не использует кэш — клонирование выполняется при каждом запросе.
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
    public GraphBuildStats local(@RequestBody @Valid RemoteProjectRequest request) {
        ProjectSourceProvider provider = new GitLabLocalSourceProvider(gatewayRepo, request);
        ProjectGraph graph = graphService.buildGraph(provider);
        return toStats(request, "workspaceDir", graph);
    }

    // ------------------------------------------------------------------
    // /cached  — clone once, patch on new commits
    // ------------------------------------------------------------------

    @Operation(
            summary = "Построить граф с кэшем (инкрементально)",
            description = """
                    При первом запросе клонирует ветку и строит полный граф.
                    При повторных запросах: если SHA не изменился — возвращает кэш; если появились
                    новые коммиты — инкрементально перестраивает только изменённые файлы.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))),
                    @ApiResponse(responseCode = "500", description = "Ошибка клонирования или построения графа")
            }
    )
    @PostMapping(value = "/cached",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public GraphBuildStats cached(@RequestBody @Valid RemoteProjectRequest request) {
        ProjectKey key = new ProjectKey(
                request.namespace(), request.repo(),
                request.branch() == null || request.branch().isBlank() ? "master" : request.branch());

        ProjectGraph graph = cachedService.getOrBuildGraph(key, request.token());
        return toStats(request, "(cached clone)", graph);
    }

    // ------------------------------------------------------------------
    // /sync  — explicit pull + incremental patch
    // ------------------------------------------------------------------

    @Operation(
            summary = "Синхронизировать ветку и обновить граф",
            description = """
                    Выполняет git pull для указанной ветки и инкрементально перестраивает граф
                    по изменённым файлам. Подходит для вебхука по push-событию или ручного обновления.
                    Возвращает список изменённых .java файлов.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Пустой список — нет изменений; иначе — список репарсенных .java файлов",
                            content = @Content(schema = @Schema(implementation = List.class))),
                    @ApiResponse(responseCode = "500", description = "Ошибка синхронизации")
            }
    )
    @PostMapping(value = "/sync",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> sync(@RequestBody @Valid RemoteProjectRequest request) {
        ProjectKey key = new ProjectKey(
                request.namespace(), request.repo(),
                request.branch() == null || request.branch().isBlank() ? "master" : request.branch());

        List<String> changed = cachedService.syncAndGetChanges(key, request.token());

        if (!changed.isEmpty()) {
            // Trigger incremental patch so the graph is up-to-date after sync
            cachedService.getOrBuildGraph(key, request.token());
        }

        log.info("GraphApiController.sync: {} — {} files changed", key, changed.size());
        return changed;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

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
