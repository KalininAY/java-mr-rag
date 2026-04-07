package com.example.mrrag.controller;

import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.AstGraphService.GraphNode;
import com.example.mrrag.service.GraphViewBuilder;
import com.example.mrrag.service.graph.AstGraphI;
import com.example.mrrag.service.source.LocalCloneSourcesProvider;
import com.example.mrrag.service.source.SourcesProvider;
import com.example.mrrag.service.dto.ProjectSourceDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Debug endpoints for inspecting the Spoon AST graph.
 */
@Slf4j
@RestController
@RequestMapping("/debug/graph")
@RequiredArgsConstructor
@Tag(name = "Граф (отладка)", description = "Эндпоинты для отладки и инспекции AST-графа: статистика, узлы по файлу и строке")
public class GraphDebugController {

    private final AstGraphI graphService;

    // ------------------------------------------------------------------
    // GET /debug/graph/stats
    // ------------------------------------------------------------------

    @Operation(
        summary = "Статистика графа",
        description = """
            Возвращает общую статистику AST-графа для указанной директории проекта:
            количество узлов и рёбер с разбивкой по типам (NodeKind / EdgeKind),
            а также список всех проиндексированных файлов.
            """,
        parameters = @Parameter(
            name = "repoDir",
            description = "Абсолютный путь к директории склонированного репозитория на диске",
            example = "/tmp/mr-rag-workspace/repos/my-repo/main",
            required = true
        ),
        responses = {
            @ApiResponse(responseCode = "200", description = "Статистика успешно получена"),
            @ApiResponse(responseCode = "500", description = "Ошибка при построении или чтении графа")
        }
    )
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam String repoDir) throws Exception {
        ProjectGraph graph = buildGraphFromDir(repoDir);
        GraphViewBuilder.ViewGraph build = new GraphViewBuilder().build(graph);

        Map<String, Long> byKind = graph.nodes.values().stream()
                .collect(Collectors.groupingBy(n -> n.kind().name(), Collectors.counting()));
        Map<String, Long> edgesByKind = graph.edgesFrom.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.groupingBy(e -> e.kind().name(), Collectors.counting()));

        return Map.of(
                "repoDir",      repoDir,
                "totalNodes",   graph.nodes.size(),
                "totalEdgeSrc", graph.edgesFrom.size(),
                "nodesByKind",  byKind,
                "edgesByKind",  edgesByKind,
                "indexedFiles", new TreeSet<>(graph.byFile.keySet())
        );
    }

    // ------------------------------------------------------------------
    // GET /debug/graph/file
    // ------------------------------------------------------------------

    @Operation(
        summary = "Узлы графа по файлу",
        description = """
            Нормализует путь файла из GitLab diff-формата до пути в графе и возвращает
            список всех AST-узлов, принадлежащих этому файлу: классы, методы, поля и т.д.
            Полезно для отладки маппинга путей между GitLab diff и локальным графом.
            """,
        parameters = {
            @Parameter(name = "repoDir",  description = "Абсолютный путь к директории склонированного репозитория", example = "/tmp/mr-rag-workspace/repos/my-repo/main", required = true),
            @Parameter(name = "diffPath", description = "Путь к файлу в формате GitLab diff (относительный путь в репозитории)", example = "src/main/java/com/example/Foo.java", required = true)
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "Список узлов файла и результат нормализации пути"),
            @ApiResponse(responseCode = "500", description = "Ошибка при построении или чтении графа")
        }
    )
    @GetMapping("/file")
    public Map<String, Object> file(@RequestParam String repoDir,
                                    @RequestParam String diffPath) throws Exception {
        ProjectGraph graph = buildGraphFromDir(repoDir);
        String normalized = graphService.normalizeFilePath(diffPath, graph);
        List<GraphNode> byFile = graph.byFile.getOrDefault(normalized, List.of());

        return Map.of(
                "diffPath",       diffPath,
                "normalizedPath", normalized,
                "matched",        !normalized.equals(diffPath) || graph.byFile.containsKey(normalized),
                "nodeCount",      byFile.size(),
                "nodes",          byFile.stream()
                        .map(n -> Map.of(
                                "id",    n.id(),
                                "kind",  n.kind().name(),
                                "name",  n.simpleName(),
                                "lines", n.startLine() + "-" + n.endLine()
                        ))
                        .collect(Collectors.toList())
        );
    }

    // ------------------------------------------------------------------
    // GET /debug/graph/line
    // ------------------------------------------------------------------

    @Operation(
        summary = "Узлы и рёбра на конкретной строке файла",
        description = """
            Возвращает все AST-узлы, чей диапазон строк охватывает указанную строку,
            а также все исходящие рёбра из этих узлов, зафиксированные именно на этой строке.
            Удобно для понимания, какие символы задействованы в конкретном изменении diff.
            """,
        parameters = {
            @Parameter(name = "repoDir",  description = "Абсолютный путь к директории склонированного репозитория", example = "/tmp/mr-rag-workspace/repos/my-repo/main", required = true),
            @Parameter(name = "diffPath", description = "Путь к файлу в формате GitLab diff", example = "src/main/java/com/example/Foo.java", required = true),
            @Parameter(name = "line",     description = "Номер строки (1-based)", example = "42", required = true)
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "Список охватывающих узлов и рёбер на указанной строке"),
            @ApiResponse(responseCode = "500", description = "Ошибка при построении или чтении графа")
        }
    )
    @GetMapping("/line")
    public Map<String, Object> line(@RequestParam String repoDir,
                                    @RequestParam String diffPath,
                                    @RequestParam int line) throws Exception {
        ProjectGraph graph = buildGraphFromDir(repoDir);
        String normalized = graphService.normalizeFilePath(diffPath, graph);
        List<GraphNode> enclosing = graph.nodesAtLine(normalized, line);

        List<Map<String, Object>> edgesAtLine = enclosing.stream()
                .flatMap(enc -> graph.outgoing(enc.id()).stream()
                        .filter(e -> normalized.equals(e.filePath()) && e.line() == line)
                        .map(e -> {
                            GraphNode target = graph.nodes.get(e.callee());
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("fromNode",   enc.id());
                            m.put("edgeKind",   e.kind().name());
                            m.put("callee",     e.callee());
                            m.put("toResolved", target != null);
                            if (target != null) {
                                m.put("toFile",  target.filePath());
                                m.put("toLines", target.startLine() + "-" + target.endLine());
                                m.put("toKind",  target.kind().name());
                            }
                            return m;
                        })
                )
                .collect(Collectors.toList());

        return Map.of(
                "diffPath",       diffPath,
                "normalizedPath", normalized,
                "line",           line,
                "enclosingNodes", enclosing.stream()
                        .map(n -> Map.of(
                                "id",   n.id(),
                                "kind", n.kind().name(),
                                "name", n.simpleName(),
                                "range", n.startLine() + "-" + n.endLine()
                        ))
                        .collect(Collectors.toList()),
                "edgesAtLine",    edgesAtLine
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ProjectGraph buildGraphFromDir(String repoDir) throws Exception {
        SourcesProvider provider = new LocalCloneSourcesProvider(Path.of(repoDir));
        ProjectSourceDto dto = provider.getProjectSourceDto();
        return graphService.buildGraph(dto);
    }
}
