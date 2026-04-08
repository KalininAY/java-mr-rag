package com.example.mrrag.app.controller;

import com.example.mrrag.app.source.CloneProjectSourceProvider;
import com.example.mrrag.app.source.LocalProjectSourceProvider;
import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.app.source.GitLabProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.review.spi.CodeRepositoryGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint for <strong>no-clone</strong> graph building via GitLab API.
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Tag(name = "Граф (GitLab API)", description = "Построение AST-графа напрямую через GitLab API без клонирования репозитория")
public class GraphApiController {

    private final AstGraphService graphService;
    private final CodeRepositoryGateway gatewayRepo;

    @Value("${app.workspace.dir:/tmp/mr-rag-workspace}")
    private String workspaceDir;

    // -----------------------------------------------------------------------
    // DTO
    // -----------------------------------------------------------------------

    @Schema(description = "Запрос на построение графа по ref через GitLab API (без клонирования)")
    public record IngestRefRequest(
            @Schema(description = "Числовой идентификатор проекта в GitLab", example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
            Long projectId,
            @Schema(description = "Ветка, тег или полный/сокращённый SHA коммита", example = "main", requiredMode = Schema.RequiredMode.REQUIRED)
            String ref
    ) {
    }

    // -----------------------------------------------------------------------
    // Endpoint
    // -----------------------------------------------------------------------

    @Operation(
            summary = "Построить граф по ref (без клонирования)",
            description = """
                    Загружает исходные файлы Java-проекта через GitLab Repository Files API и строит
                    AST-граф символов (узлы + рёбра). Клонирование не выполняется — файлы читаются
                    напрямую из GitLab. Подходит для быстрого анализа без дискового пространства.
                    """,
            requestBody = @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestRefRequest.class),
                            examples = @ExampleObject(
                                    name = "Пример запроса",
                                    value = """
                                            {
                                              "projectId": 123,
                                              "ref": "main"
                                            }"""
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Граф успешно построен. Возвращает статистику узлов и рёбер.",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))
                    ),
                    @ApiResponse(responseCode = "400", description = "Некорректный запрос: не указан projectId или ref"),
                    @ApiResponse(responseCode = "500", description = "Ошибка при обращении к GitLab API или построении графа")
            }
    )
    @PostMapping(
            value = "/ingest-ref",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingestRef(@org.springframework.web.bind.annotation.RequestBody IngestRefRequest req) throws Exception {

        if (req.projectId() == null)
            throw new IllegalArgumentException("projectId must not be null");
        if (req.ref() == null || req.ref().isBlank())
            throw new IllegalArgumentException("ref must not be blank");

        long wallStart = System.currentTimeMillis();

        ProjectSourceProvider provider =
                new GitLabProjectSourceProvider(gatewayRepo, req.projectId(), req.ref());

        long fetchStart = System.currentTimeMillis();
        ProjectGraph graph = graphService.buildGraph(provider);
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
                req.projectId, req.ref,
                "(virtual — no clone)",
                buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }


    @Operation(
            summary = "Клонировать репозиторий и построить граф",
            description = """
                    Клонирует Git-репозиторий в персистентную директорию рабочего пространства
                    (${app.workspace.dir}/repos/<slug>/<branch>) с помощью JGit (без внешнего процесса git),
                    затем строит AST-граф символов Java-проекта.
                    """,
            requestBody = @RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = IngestRefRequest.class),
                            examples = @ExampleObject(
                                    name = "Пример запроса",
                                    value = """
                                            {
                                              "projectId": 123,
                                              "ref": "main"
                                            }"""
                            )
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Граф успешно построен. Возвращает статистику: количество узлов/рёбер, время клонирования и сборки.",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))
                    ),
                    @ApiResponse(responseCode = "400", description = "Некорректный запрос: repoUrl не указан или пуст"),
                    @ApiResponse(responseCode = "500", description = "Ошибка клонирования (недоступный репозиторий, неверный токен) или построения графа")
            }
    )
    @PostMapping(
            value = "/ingest",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats ingest(@RequestBody IngestRefRequest request) throws Exception {
        if (request.projectId() == null)
            throw new IllegalArgumentException("projectId must not be null");
        if (request.ref() == null || request.ref().isBlank())
            throw new IllegalArgumentException("ref must not be blank");
        long wallStart = System.currentTimeMillis();

        ProjectSourceProvider sourceProvider = new CloneProjectSourceProvider(gatewayRepo, Path.of(workspaceDir), request.projectId, request.ref);

        long buildStart = System.currentTimeMillis();
        ProjectGraph graph = graphService.buildGraph(sourceProvider);
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

        return new GraphBuildStats(
                request.projectId, request.ref,
                workspaceDir,
                buildMs, totalMs,
                graph.nodes.size(), totalEdges,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }
}
