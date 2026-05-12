package com.example.mrrag.app.controller;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.source.GitLabLocalSourceProvider;
import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.app.service.AstGraphService;
import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.app.source.GitLabRemoteSourceProvider;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.app.repo.CodeRepositoryGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST endpoint for <strong>no-clone</strong> graph building via GitLab API.
 */
@Slf4j
@RestController
@Validated
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Tag(name = "Граф (GitLab API)", description = "Построение AST-графа напрямую через GitLab API без клонирования репозитория")
public class GraphApiController {

    private final AstGraphService graphService;
    private final CodeRepositoryGateway gatewayRepo;
    private final GraphQueryService graphQueryService;

    @Operation(
            summary = "Построить граф по revision (без клонирования)",
            description = """
                    Загружает исходные файлы Java-проекта через GitLab Repository Files API и строит
                    AST-граф символов (узлы + рёбра). Клонирование не выполняется — файлы читаются
                    напрямую из GitLab. Подходит для быстрого анализа без дискового пространства.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RemoteProjectRequest.class)
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Граф успешно построен. Возвращает статистику узлов и рёбер.",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))
                    ),
                    @ApiResponse(responseCode = "500", description = "Ошибка при обращении к GitLab API или построении графа")
            }
    )
    @PostMapping(
            value = "/remote",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats remote(@RequestBody @Valid RemoteProjectRequest request) throws Exception {

        ProjectSourceProvider provider =
                new GitLabRemoteSourceProvider(gatewayRepo, request);

        ProjectGraph graph = graphService.buildGraph(provider, request.force());

        Map<NodeKind, Long> nodesByKind = Arrays.stream(NodeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.nodes.values().stream().filter(n -> n.kind() == k).count()));
        Map<EdgeKind, Long> edgesByKind = Arrays.stream(EdgeKind.values())
                .collect(Collectors.toMap(k -> k,
                        k -> graph.edgesFrom.values().stream()
                                .flatMap(java.util.List::stream)
                                .filter(e -> e.kind() == k).count()));

        return new GraphBuildStats(
                request.namespace(), request.repo(), request.branch(),
                "(virtual — no clone)",
                0, 0,
                graph.nodes.size(), 0,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }


    @Operation(
            summary = "Клонировать репозиторий и построить граф",
            description = """
                     Клонирует Git-репозиторий в рабочее пространство и строит\s
                     AST-граф Java-проекта с использованием JGit.
                    \s""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RemoteProjectRequest.class)
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Граф успешно построен. Возвращает статистику: количество узлов/рёбер, время клонирования и сборки",
                            content = @Content(schema = @Schema(implementation = GraphBuildStats.class))
                    ),
                    @ApiResponse(responseCode = "500", description = "Ошибка клонирования (недоступный репозиторий, неверный токен) или построения графа")
            }
    )
    @PostMapping(
            value = "/local",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GraphBuildStats local(@RequestBody @Valid RemoteProjectRequest request) throws Exception {

        ProjectSourceProvider sourceProvider =
                new GitLabLocalSourceProvider(gatewayRepo, request);

        ProjectGraph graph = graphService.buildGraph(sourceProvider, request.force());

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


        return new GraphBuildStats(
                request.namespace(), request.repo(), request.branch(),
                "workspaceDir",
                0, 0,
                graph.nodes.size(), 0,
                nodesByKind, edgesByKind,
                graph.byFile.size());
    }

    // ------------------------------------------------------------------
    // GET /api/graph/node/by-location
    // ------------------------------------------------------------------

    @Operation(
            summary = "Найти ноду графа по файлу и строке",
            description = """
                    Возвращает AST-узлы графа, охватывающие указанную строку в файле.
                    Использует уже построенный (закэшированный) граф — не перестраивает его.
                    Предназначен для агентов, которым известен только filePath + lineNumber из diff,
                    но не известен nodeId.

                    Логика выбора нод соответствует стратегии GraphQueryService.getNodesWithLine:
                    - узлы без тела (FIELD, VARIABLE и т.д.) + вызовы на строке имеют приоритет;
                    - если таких нет — возвращается наименьший METHOD/LAMBDA, покрывающий строку.
                    """,
            parameters = {
                    @Parameter(name = "namespace", description = "Namespace проекта в GitLab", example = "mygroup/subgroup", required = true),
                    @Parameter(name = "repo",      description = "Название репозитория",         example = "myrepo",          required = true),
                    @Parameter(name = "branch",    description = "Ветка (необязательно)",        example = "main"),
                    @Parameter(name = "commit",    description = "SHA коммита (необязательно)",  example = "a1b2c3d"),
                    @Parameter(name = "token",     description = "PAT; если null — из конфига"),
                    @Parameter(name = "filePath",  description = "Относительный путь к файлу (как в GitLab diff)", example = "src/main/java/com/example/Foo.java", required = true),
                    @Parameter(name = "line",      description = "Номер строки (1-based)", example = "42", required = true)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Список нод, покрывающих строку"),
                    @ApiResponse(responseCode = "404", description = "Граф не найден в кэше или нода не найдена"),
                    @ApiResponse(responseCode = "400", description = "Некорректные параметры запроса")
            }
    )
    @GetMapping("/node/by-location")
    public List<Map<String, Object>> nodeByLocation(
            @RequestParam @NotBlank String namespace,
            @RequestParam @NotBlank String repo,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commit,
            @RequestParam(required = false) String token,
            @RequestParam @NotBlank String filePath,
            @RequestParam @Min(1) int line) throws Exception {

        RemoteProjectRequest request = new RemoteProjectRequest(namespace, repo, branch, commit, token, false);
        ProjectSourceProvider provider = new GitLabRemoteSourceProvider(gatewayRepo, request);
        ProjectGraph graph = graphService.buildGraph(provider, false);

        String normalized = AstGraphUtils.normalizeFilePath(filePath, graph);
        List<GraphNode> nodes = graphQueryService.getNodesWithLine(normalized, line, graph);

        if (nodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No nodes found at " + filePath + ":" + line);
        }

        return nodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId",    n.id());
            m.put("kind",      n.kind().name());
            m.put("filePath",  n.filePath());
            m.put("startLine", n.startLine());
            m.put("endLine",   n.endLine());
            m.put("simpleName", n.simpleName());
            if (n.sourceSnippet() != null && !n.sourceSnippet().isBlank()) {
                m.put("sourceSnippet", n.sourceSnippet());
            }
            return m;
        }).collect(Collectors.toList());
    }
}
