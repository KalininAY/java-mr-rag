package com.example.mrrag.graph;

import com.example.mrrag.commons.source.ProjectSourceProvider;

import java.nio.file.Path;
import java.util.*;

/**
 * Primary contract for building an AST symbol graph.
 *
 * <p>Implementations receive Java source files through a
 * {@link ProjectSourceProvider} and must not care about how those files
 * are obtained (local clone, GitLab API, test fixtures, etc.).
 */
public interface GraphBuilder {

    /**
     * Build (or return a cached) symbol graph from any source provider.
     *
     * <p>The provider abstraction means this method works identically for
     * local clones, GitLab API responses, or any future VCS backend.
     *
     * @param provider supplies the raw {@code .java} files to analyse
     * @return fully populated (or partial) {@link ProjectGraph}
     * @throws Exception on any IO / API / parse error
     */
    ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception;

    /**
     * Evict any cached graph for the given local clone directory.
     *
     * @param projectRoot the clone root previously passed to
     */
    void invalidate(Path projectRoot);



    // ------------------------------------------------------------------
    // Public model
    // ------------------------------------------------------------------

    enum NodeKind {
        CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
        LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE
    }

    enum EdgeKind {
        DECLARES, EXTENDS, IMPLEMENTS,
        INVOKES, INSTANTIATES, INSTANTIATES_ANONYMOUS, REFERENCES_METHOD,
        READS_FIELD, WRITES_FIELD,
        READS_LOCAL_VAR, WRITES_LOCAL_VAR,
        THROWS, ANNOTATED_WITH, REFERENCES_TYPE, OVERRIDES,
        HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR
    }

    record GraphNode(
            String id, NodeKind kind, String simpleName,
            String filePath, int startLine, int endLine,
            String sourceSnippet, String declarationSnippet) {}

    record GraphEdge(
            String caller, EdgeKind kind, String callee,
            String filePath, int line) {}

    class ProjectGraph {
        public final Map<String, GraphNode> nodes        = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesFrom    = new LinkedHashMap<>();
        public final Map<String, List<GraphEdge>> edgesTo      = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> bySimpleName = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byLine       = new LinkedHashMap<>();
        public final Map<String, List<GraphNode>> byFile       = new LinkedHashMap<>();

        public Set<String> allFilePaths() { return byFile.keySet(); }

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

        public List<GraphEdge> outgoing(String id)              { return edgesFrom.getOrDefault(id, List.of()); }
        public List<GraphEdge> incoming(String id)              { return edgesTo.getOrDefault(id, List.of()); }
        public List<GraphEdge> outgoing(String id, EdgeKind k)  { return outgoing(id).stream().filter(e -> e.kind() == k).toList(); }
        public List<GraphEdge> incoming(String id, EdgeKind k)  { return incoming(id).stream().filter(e -> e.kind() == k).toList(); }

        public List<GraphNode> nodesAtLine(String relPath, int line) {
            return byFile.getOrDefault(relPath, List.of()).stream()
                    .filter(n -> n.startLine() <= line && n.endLine() >= line)
                    .toList();
        }

        /** Reconstruct a {@link ProjectGraph} from flat node/edge lists (used by deserialization). */
        public static ProjectGraph reconstruct(List<GraphNode> nodes, List<GraphEdge> edges) {
            ProjectGraph g = new ProjectGraph();
            for (GraphNode n : nodes) g.addNode(n);
            for (GraphEdge e : edges) g.addEdge(e);
            return g;
        }
    }
}
