package com.example.mrrag.model.graph;

import com.example.mrrag.service.AstGraphService;

import java.util.*;

/**
 * The full symbol graph of a project.
 */
public class ProjectGraph {
    public final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    public final Map<String, List<GraphEdge>> edgesFrom = new LinkedHashMap<>();
    public final Map<String, List<GraphEdge>> edgesTo = new LinkedHashMap<>();
    public final Map<String, List<GraphNode>> bySimpleName = new LinkedHashMap<>();
    public final Map<String, List<GraphNode>> byLine = new LinkedHashMap<>();
    public final Map<String, List<GraphNode>> byFile = new LinkedHashMap<>();

    /**
     * All file paths stored in the graph (relative to the project root that
     * was passed to {@link AstGraphService#buildGraph}).
     * Used by {@link AstGraphService#normalizeFilePath} for suffix matching.
     */
    public Set<String> allFilePaths() {
        return byFile.keySet();
    }

    public void addNode(GraphNode n) {
        nodes.put(n.id(), n);
        bySimpleName.computeIfAbsent(n.simpleName(), k -> new ArrayList<>()).add(n);
        byLine.computeIfAbsent(n.filePath() + "#" + n.startLine(), k -> new ArrayList<>()).add(n);
        byFile.computeIfAbsent(n.filePath(), k -> new ArrayList<>()).add(n);
    }

    public void addEdge(GraphEdge e) {
        edgesFrom.computeIfAbsent(e.caller(), k -> new ArrayList<>()).add(e);
        edgesTo.computeIfAbsent(e.callee(), k -> new ArrayList<>()).add(e);
    }

    public List<GraphEdge> outgoing(String nodeId) {
        return edgesFrom.getOrDefault(nodeId, List.of());
    }

    public List<GraphEdge> incoming(String nodeId) {
        return edgesTo.getOrDefault(nodeId, List.of());
    }

    public List<GraphEdge> outgoing(String nodeId, EdgeKind kind) {
        return outgoing(nodeId).stream().filter(e -> e.kind() == kind).toList();
    }

    public List<GraphEdge> incoming(String nodeId, EdgeKind kind) {
        return incoming(nodeId).stream().filter(e -> e.kind() == kind).toList();
    }

    /**
     * All nodes whose line-range covers the given line in a file.
     */
    public List<GraphNode> nodesAtLine(String relPath, int line) {
        return byFile.getOrDefault(relPath, List.of()).stream()
                .filter(n -> n.startLine() <= line && n.endLine() >= line)
                .toList();
    }
}
