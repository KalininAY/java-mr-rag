package com.example.mrrag.controller;

import com.example.mrrag.service.AstGraphProvider;
import com.example.mrrag.service.AstGraphService;
import com.example.mrrag.model.graph.ProjectGraph;
import com.example.mrrag.model.graph.GraphNode;
import com.example.mrrag.service.GraphViewBuilder;
import com.example.mrrag.service.SourceProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Debug endpoints for inspecting the Spoon AST graph.
 *
 * <pre>
 * GET  /debug/graph/stats?repoDir=...
 *      — overall node/edge counts, breakdown by kind, list of indexed files
 *
 * GET  /debug/graph/file?repoDir=...&diffPath=...
 *      — show how a GitLab diff path is normalized, and list all nodes in that file
 *
 * GET  /debug/graph/line?repoDir=...&diffPath=...&line=N
 *      — show all graph nodes whose range covers line N, plus all outgoing edges from those nodes at line N
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/debug/graph")
@RequiredArgsConstructor
public class GraphDebugController {

    private final AstGraphProvider graphService;
    private final SourceProvider sourceProvider;

    // ------------------------------------------------------------------
    // GET /debug/graph/stats?repoDir=/tmp/repo-123/source
    // ------------------------------------------------------------------

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam String repoDir) {
        Path root = Path.of(repoDir);
        List<String> sources = sourceProvider.sourceProvider(root);
        ProjectGraph graph = graphService.buildGraph(root.toString(), sources);

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
    // GET /debug/graph/file?repoDir=...&diffPath=gl-hooks/src/main/java/Foo.java
    // ------------------------------------------------------------------

    @GetMapping("/file")
    public Map<String, Object> file(
            @RequestParam String repoDir,
            @RequestParam String diffPath
    ) {
        Path root = Path.of(repoDir);
        List<String> sources = sourceProvider.sourceProvider(root);
        ProjectGraph graph = graphService.buildGraph(root.toString(), sources);

        String normalized = graphService.normalizeFilePath(diffPath, graph);
        List<GraphNode> nodes = graph.byFile.getOrDefault(normalized, List.of());

        return Map.of(
                "diffPath",       diffPath,
                "normalizedPath", normalized,
                "matched",        !normalized.equals(diffPath) || graph.byFile.containsKey(normalized),
                "nodeCount",      nodes.size(),
                "nodes",          nodes.stream()
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
    // GET /debug/graph/line?repoDir=...&diffPath=...&line=42
    // ------------------------------------------------------------------

    @GetMapping("/line")
    public Map<String, Object> line(
            @RequestParam String repoDir,
            @RequestParam String diffPath,
            @RequestParam int line
    ) {
        Path root = Path.of(repoDir);
        ProjectGraph graph = graphService.buildGraph(root);

        String normalized = graphService.normalizeFilePath(diffPath, graph);
        List<GraphNode> enclosing = graph.nodesAtLine(normalized, line);

        // All outgoing edges from enclosing nodes that were recorded at this exact line
        List<Map<String, Object>> edgesAtLine = enclosing.stream()
                .flatMap(enc -> graph.outgoing(enc.id()).stream()
                        .filter(e -> normalized.equals(e.filePath()) && e.line() == line)
                        .map(e -> {
                            GraphNode target = graph.nodes.get(e.callee());
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("fromNode",   enc.id());
                            m.put("edgeKind",   e.kind().name());
                            m.put("callee",       e.callee());
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
}
