package com.example.mrrag.app.mcp;

import com.example.mrrag.app.controller.requestDTO.RemoteProjectRequest;
import com.example.mrrag.app.source.ProjectKey;
import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.GraphBuildStats;
import com.example.mrrag.graph.GraphQueryService;
import com.example.mrrag.graph.cache.CachedManagementService;
import com.example.mrrag.graph.markdown.GraphMarkdownRenderer;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP-инструменты (Spring AI @Tool), дублирующие эндпоинты {@link com.example.mrrag.app.controller.GraphApiController}.
 * Регистрируются как MCP-сервер и доступны для LLM-агентов через протокол MCP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphApiMcpTools {

    private final CachedManagementService cachedService;
    private final GraphQueryService graphQueryService;

    // ------------------------------------------------------------------
    // POST /api/graph/build
    // ------------------------------------------------------------------

    @Tool(name = "graph_build",
          description = """
                  Клонирует ветку репозитория GitLab и строит полный AST-граф.
                  Повторный вызов с теми же namespace/repo/branch: если SHA не изменился —
                  возвращает граф из памяти (инкрементальное обновление).
                  Возвращает статистику построения: количество узлов/рёбер по видам.
                  """)
    public GraphBuildStats buildGraph(
            @ToolParam(description = "Namespace проекта в GitLab, например: mygroup/subgroup") String namespace,
            @ToolParam(description = "Название репозитория, например: myrepo") String repo,
            @ToolParam(description = "Ветка, например: main") String branch,
            @ToolParam(description = "GitLab Personal Access Token (необязателен для публичных проектов)", required = false) String token,
            @ToolParam(description = "URL GitLab-инстанса, например: https://gitlab.com", required = false) String gitlabUrl
    ) {
        RemoteProjectRequest request = new RemoteProjectRequest(namespace, repo, branch, token, gitlabUrl);
        ProjectKey key = ProjectKey.from(request);
        ProjectGraph graph = cachedService.getOrBuildGraph(key, request.token());
        return toStats(request, graph);
    }

    // ------------------------------------------------------------------
    // GET /api/graph/node
    // ------------------------------------------------------------------

    @Tool(name = "graph_get_node",
          description = """
                  Возвращает карточку узла AST-графа в формате Markdown.
                  Карточка содержит: kind, simpleName, filePath, диапазон строк,
                  bodyHash, declarationSnippet и sourceSnippet (если есть).
                  Граф должен быть предварительно построен через graph_build.
                  """)
    public String getNode(
            @ToolParam(description = "Namespace проекта в GitLab") String namespace,
            @ToolParam(description = "Название репозитория") String repo,
            @ToolParam(description = "Ветка") String branch,
            @ToolParam(description = "Идентификатор узла графа (nodeId)") String nodeId
    ) {
        ProjectKey key = new ProjectKey(namespace, repo, branch);
        ProjectGraph graph = cachedService.getOrBuildGraph(key, null);
        GraphNode node = graph.nodes.get(nodeId);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId);
        }
        return GraphMarkdownRenderer.renderNode(node);
    }

    // ------------------------------------------------------------------
    // GET /api/graph/edges
    // ------------------------------------------------------------------

    @Tool(name = "graph_get_edges",
          description = """
                  Возвращает таблицу рёбер узла в формате Markdown.
                  from=true  — исходящие рёбра (вызовы, которые делает нода).
                  from=false — входящие рёбра (кто ссылается на данную ноду).
                  """)
    public String getEdges(
            @ToolParam(description = "Namespace проекта в GitLab") String namespace,
            @ToolParam(description = "Название репозитория") String repo,
            @ToolParam(description = "Ветка") String branch,
            @ToolParam(description = "Идентификатор узла графа (nodeId)") String nodeId,
            @ToolParam(description = "true — исходящие рёбра, false — входящие") boolean from
    ) {
        ProjectKey key = new ProjectKey(namespace, repo, branch);
        ProjectGraph graph = cachedService.getOrBuildGraph(key, null);

        if (!graph.nodes.containsKey(nodeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId);
        }

        List<GraphEdge> edges = from
                ? graph.outgoing(nodeId)
                : graph.incoming(nodeId);

        return GraphMarkdownRenderer.renderEdges(nodeId, edges, from);
    }

    // ------------------------------------------------------------------
    // GET /api/graph/node/by-location
    // ------------------------------------------------------------------

    @Tool(name = "graph_node_by_location",
          description = """
                  Находит узлы AST-графа по файлу и номеру строки.
                  Использует уже построенный (закэшированный) граф — не перестраивает его.
                  Предназначен для агентов, которым известен только filePath + lineNumber из diff,
                  но не известен nodeId.
                  
                  Логика: якорь (METHOD/LAMBDA/CONSTRUCTOR) + callee рёбер на строке — приоритет;
                  если ничего не нашлось — fallback на наименьший охватывающий узел.
                  Возвращает список объектов с полями: nodeId, kind, filePath, startLine, endLine,
                  simpleName, sourceSnippet (если есть).
                  """)
    public List<Map<String, Object>> nodeByLocation(
            @ToolParam(description = "Namespace проекта в GitLab") String namespace,
            @ToolParam(description = "Название репозитория") String repo,
            @ToolParam(description = "Ветка") String branch,
            @ToolParam(description = "Относительный путь к файлу (как в GitLab diff), например: src/main/java/com/example/Foo.java") String filePath,
            @ToolParam(description = "Номер строки (1-based), например: 42") int line
    ) {
        ProjectKey key = new ProjectKey(namespace, repo, branch);
        ProjectGraph graph = cachedService.getOrBuildGraph(key, null);

        String normalized = AstGraphUtils.normalizeFilePath(filePath, graph);
        List<GraphNode> nodes = graphQueryService.getNodesWithLine(normalized, line, graph);

        if (nodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No nodes found at " + filePath + ":" + line);
        }

        return nodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId",      n.id());
            m.put("kind",        n.kind().name());
            m.put("filePath",    n.filePath());
            m.put("startLine",   n.startLine());
            m.put("endLine",     n.endLine());
            m.put("simpleName",  n.simpleName());
            if (n.sourceSnippet() != null && !n.sourceSnippet().isBlank()) {
                m.put("sourceSnippet", n.sourceSnippet());
            }
            return m;
        }).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

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
