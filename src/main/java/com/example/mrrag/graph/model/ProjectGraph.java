package com.example.mrrag.graph.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe symbol graph.
 *
 * <p>All collections use concurrent implementations so that
 * {@link com.example.mrrag.graph.GraphBuilderImpl} can populate
 * the graph from multiple threads simultaneously.
 */
public class ProjectGraph {
    public final Map<String, GraphNode>        nodes        = new ConcurrentHashMap<>();
    public final Map<String, List<GraphEdge>>  edgesFrom    = new ConcurrentHashMap<>();
    public final Map<String, List<GraphEdge>>  edgesTo      = new ConcurrentHashMap<>();
    public final Map<String, List<GraphNode>>  bySimpleName = new ConcurrentHashMap<>();
    public final Map<String, List<GraphNode>>  byLine       = new ConcurrentHashMap<>();
    public final Map<String, List<GraphNode>>  byFile       = new ConcurrentHashMap<>();

    public Set<String> allFilePaths() {
        return byFile.keySet();
    }

    public void addNode(GraphNode n) {
        nodes.put(n.id(), n);
        bySimpleName.computeIfAbsent(n.simpleName(),             k -> new CopyOnWriteArrayList<>()).add(n);
        byLine      .computeIfAbsent(n.filePath() + "#" + n.startLine(), k -> new CopyOnWriteArrayList<>()).add(n);
        byFile      .computeIfAbsent(n.filePath(),                k -> new CopyOnWriteArrayList<>()).add(n);
    }

    public void addEdge(GraphEdge e) {
        edgesFrom.computeIfAbsent(e.caller(), k -> new CopyOnWriteArrayList<>()).add(e);
        edgesTo  .computeIfAbsent(e.callee(), k -> new CopyOnWriteArrayList<>()).add(e);
    }

    public List<GraphEdge> outgoing(String id) {
        return edgesFrom.getOrDefault(id, List.of());
    }

    public List<GraphEdge> incoming(String id) {
        return edgesTo.getOrDefault(id, List.of());
    }

    public List<GraphEdge> outgoing(String id, EdgeKind k) {
        return outgoing(id).stream().filter(e -> e.kind() == k).toList();
    }

    public List<GraphEdge> incoming(String id, EdgeKind k) {
        return incoming(id).stream().filter(e -> e.kind() == k).toList();
    }

    public List<GraphNode> nodesAtLine(String relPath, int line) {
        return byFile.getOrDefault(relPath, List.of()).stream()
                .filter(n -> n.startLine() <= line && n.endLine() >= line)
                .toList();
    }

    /**
     * Reconstruct a {@link ProjectGraph} from flat node/edge lists (used by deserialization).
     */
    public static ProjectGraph reconstruct(List<GraphNode> nodes, List<GraphEdge> edges) {
        ProjectGraph g = new ProjectGraph();
        for (GraphNode n : nodes) g.addNode(n);
        for (GraphEdge e : edges) g.addEdge(e);
        return g;
    }
}
