package com.example.mrrag.service;

import com.example.mrrag.app.config.EdgeKindConfig;
import com.example.mrrag.app.config.GraphCacheProperties;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.GraphRawBuilder;
import com.example.mrrag.graph.GraphRawBuilder.ProjectGraphRaw;
import com.example.mrrag.graph.raw.ProjectGraphCacheStore;
import com.example.mrrag.graph.raw.ProjectKey;
import com.example.mrrag.graph.raw.source.ProjectSourceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Facade over {@link GraphRawBuilder} that preserves the historical
 * {@code AstGraphService} API used by tests and controllers.
 *
 * <p>All type aliases ({@link ProjectGraph}, {@link GraphNode}, {@link GraphEdge},
 * {@link NodeKind}, {@link EdgeKind}) forward to their counterparts inside
 * {@link GraphRawBuilder} so existing call-sites require no changes.
 */
@Slf4j
@Service
public class AstGraphService {

    // ------------------------------------------------------------------
    // Public type aliases (kept for backward compatibility)
    // ------------------------------------------------------------------

    /** @see GraphRawBuilder.NodeKind */
    public enum NodeKind {
        CLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD, VARIABLE,
        LAMBDA, ANNOTATION, TYPE_PARAM, ANNOTATION_ATTRIBUTE;

        public GraphRawBuilder.NodeKind toRaw() {
            return GraphRawBuilder.NodeKind.valueOf(this.name());
        }

        public static NodeKind from(GraphRawBuilder.NodeKind raw) {
            return NodeKind.valueOf(raw.name());
        }
    }

    /** @see GraphRawBuilder.EdgeKind */
    public enum EdgeKind {
        DECLARES, EXTENDS, IMPLEMENTS,
        INVOKES, INSTANTIATES, INSTANTIATES_ANONYMOUS, REFERENCES_METHOD,
        READS_FIELD, WRITES_FIELD,
        READS_LOCAL_VAR, WRITES_LOCAL_VAR,
        THROWS, ANNOTATED_WITH, REFERENCES_TYPE, OVERRIDES,
        HAS_TYPE_PARAM, HAS_BOUND, ANNOTATION_ATTR;

        public GraphRawBuilder.EdgeKind toRaw() {
            return GraphRawBuilder.EdgeKind.valueOf(this.name());
        }

        public static EdgeKind from(GraphRawBuilder.EdgeKind raw) {
            return EdgeKind.valueOf(raw.name());
        }
    }

    /**
     * Alias for {@link GraphRawBuilder.GraphNode}.
     * @see GraphRawBuilder.GraphNode
     */
    public record GraphNode(
            String id, NodeKind kind, String simpleName,
            String filePath, int startLine, int endLine,
            String sourceSnippet, String declarationSnippet) {

        public static GraphNode from(GraphRawBuilder.GraphNode raw) {
            return new GraphNode(
                    raw.id(), NodeKind.from(raw.kind()), raw.simpleName(),
                    raw.filePath(), raw.startLine(), raw.endLine(),
                    raw.sourceSnippet(), raw.declarationSnippet());
        }

        public GraphRawBuilder.GraphNode toRaw() {
            return new GraphRawBuilder.GraphNode(
                    id, kind.toRaw(), simpleName,
                    filePath, startLine, endLine,
                    sourceSnippet, declarationSnippet);
        }
    }

    /**
     * Alias for {@link GraphRawBuilder.GraphEdge}.
     * @see GraphRawBuilder.GraphEdge
     */
    public record GraphEdge(
            String caller, EdgeKind kind, String callee,
            String filePath, int line) {

        public static GraphEdge from(GraphRawBuilder.GraphEdge raw) {
            return new GraphEdge(
                    raw.caller(), EdgeKind.from(raw.kind()), raw.callee(),
                    raw.filePath(), raw.line());
        }

        public GraphRawBuilder.GraphEdge toRaw() {
            return new GraphRawBuilder.GraphEdge(
                    caller, kind.toRaw(), callee, filePath, line);
        }
    }

    /**
     * Wrapper around {@link ProjectGraphRaw} that surfaces the same
     * field and method names that tests historically expected on
     * {@code AstGraphService.ProjectGraph}.
     */
    public static class ProjectGraph {

        private final ProjectGraphRaw raw;

        public ProjectGraph(ProjectGraphRaw raw) {
            this.raw = raw;
        }

        public ProjectGraphRaw raw() { return raw; }

        // Expose maps with AstGraphService types
        public Map<String, GraphNode> nodes = new java.util.AbstractMap<>() {
            @Override
            public java.util.Set<Entry<String, GraphNode>> entrySet() {
                return new java.util.AbstractSet<>() {
                    @Override public int size() { return raw.nodes.size(); }
                    @Override public java.util.Iterator<Entry<String, GraphNode>> iterator() {
                        var it = raw.nodes.entrySet().iterator();
                        return new java.util.Iterator<>() {
                            public boolean hasNext() { return it.hasNext(); }
                            public Entry<String, GraphNode> next() {
                                var e = it.next();
                                return Map.entry(e.getKey(), GraphNode.from(e.getValue()));
                            }
                        };
                    }
                };
            }

            @Override public boolean containsKey(Object key) { return raw.nodes.containsKey(key); }

            @Override public GraphNode get(Object key) {
                var n = raw.nodes.get(key);
                return n == null ? null : GraphNode.from(n);
            }

            @Override public java.util.Set<String> keySet() { return raw.nodes.keySet(); }
        };

        public java.util.Set<String> allFilePaths() { return raw.allFilePaths(); }

        public List<GraphEdge> outgoing(String id) {
            return raw.outgoing(id).stream().map(GraphEdge::from).toList();
        }

        public List<GraphEdge> incoming(String id) {
            return raw.incoming(id).stream().map(GraphEdge::from).toList();
        }

        public List<GraphEdge> outgoing(String id, EdgeKind k) {
            return raw.outgoing(id, k.toRaw()).stream().map(GraphEdge::from).toList();
        }

        public List<GraphEdge> incoming(String id, EdgeKind k) {
            return raw.incoming(id, k.toRaw()).stream().map(GraphEdge::from).toList();
        }

        public List<GraphNode> nodesAtLine(String relPath, int line) {
            return raw.nodesAtLine(relPath, line).stream().map(GraphNode::from).toList();
        }
    }

    // ------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------

    private final GraphRawBuilder delegate;

    public AstGraphService(EdgeKindConfig edgeConfig,
                           GraphCacheProperties cacheProps,
                           ProjectGraphCacheStore cacheStore) {
        this.delegate = new GraphRawBuilder(edgeConfig, cacheProps, cacheStore);
    }

    // ------------------------------------------------------------------
    // Public API (mirrors GraphRawBuilder)
    // ------------------------------------------------------------------

    public ProjectGraph buildGraph(Path projectRoot) throws Exception {
        ProjectKey key = delegate.projectKey(projectRoot);
        return new ProjectGraph(delegate.buildGraph(key));
    }

    public ProjectGraph buildGraph(ProjectKey key) throws Exception {
        return new ProjectGraph(delegate.buildGraph(key));
    }

    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        return new ProjectGraph(delegate.buildGraph(provider));
    }

    public ProjectKey projectKey(Path projectRoot) {
        return delegate.projectKey(projectRoot);
    }

    public void invalidate(ProjectKey key) {
        delegate.invalidate(key);
    }

    public void invalidate(Path projectRoot) {
        delegate.invalidate(projectRoot);
    }

    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        return delegate.normalizeFilePath(diffPath, graph.raw());
    }

    /** Direct access to the raw builder (for callers in graph/app layer). */
    public GraphRawBuilder rawBuilder() {
        return delegate;
    }

    /** Delegates {@link GraphBuilder} interface method. */
    public GraphRawBuilder.ProjectGraphRaw buildRawGraph(ProjectSourceProvider provider) throws Exception {
        return delegate.buildGraph(provider);
    }
}
