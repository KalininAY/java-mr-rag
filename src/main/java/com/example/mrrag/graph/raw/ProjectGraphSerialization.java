package com.example.mrrag.graph.raw;

import com.example.mrrag.graph.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON snapshot of a {@link ProjectGraph} for disk cache.
 */
public final class ProjectGraphSerialization {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private ProjectGraphSerialization() {
    }

    public static byte[] toJson(ProjectGraph graph) throws IOException {
        Snapshot snap = Snapshot.from(graph);
        return MAPPER.writeValueAsBytes(snap);
    }

    public static ProjectGraph fromJson(byte[] json) throws IOException {
        Snapshot snap = MAPPER.readValue(json, Snapshot.class);
        return snap.toGraph();
    }

    public static void write(ProjectGraph graph, OutputStream out) throws IOException {
        Snapshot snap = Snapshot.from(graph);
        MAPPER.writeValue(out, snap);
    }

    public static ProjectGraph read(InputStream in) throws IOException {
        Snapshot snap = MAPPER.readValue(in, Snapshot.class);
        return snap.toGraph();
    }

    /**
     * DTO for Jackson.
     */
    public static final class Snapshot {
        public List<NodeSnapshot> nodes;
        public List<EdgeSnapshot> edges;

        Snapshot(List<NodeSnapshot> nodes, List<EdgeSnapshot> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        static Snapshot from(ProjectGraph g) {
            List<NodeSnapshot> nodes = new ArrayList<>(g.nodes.size());
            for (GraphNode n : g.nodes.values()) {
                nodes.add(NodeSnapshot.from(n));
            }
            List<EdgeSnapshot> edges = new ArrayList<>();
            for (List<GraphEdge> list : g.edgesFrom.values()) {
                for (GraphEdge e : list) {
                    edges.add(EdgeSnapshot.from(e));
                }
            }
            return new Snapshot(nodes, edges);
        }

        ProjectGraph toGraph() {
            List<NodeSnapshot> nList = nodes != null ? nodes : List.of();
            List<EdgeSnapshot> eList = edges != null ? edges : List.of();
            List<GraphNode> nodeList = new ArrayList<>();
            for (NodeSnapshot n : nList) {
                nodeList.add(n.toGraphNode());
            }
            List<GraphEdge> edgeList = new ArrayList<>();
            for (EdgeSnapshot e : eList) {
                edgeList.add(e.toGraphEdge());
            }
            return ProjectGraph.reconstruct(nodeList, edgeList);
        }
    }

    public static final class NodeSnapshot {
        public String id;
        public String kind;
        public String simpleName;
        public String filePath;
        public int startLine;
        public int endLine;
        public String sourceSnippet;
        public String declarationSnippet;

        static NodeSnapshot from(GraphNode n) {
            NodeSnapshot s = new NodeSnapshot();
            s.id = n.id();
            s.kind = n.kind().name();
            s.simpleName = n.simpleName();
            s.filePath = n.filePath();
            s.startLine = n.startLine();
            s.endLine = n.endLine();
            s.sourceSnippet = n.sourceSnippet();
            s.declarationSnippet = n instanceof NodeWithDeclaration nwd
                    ? nwd.declaration().getSourceSnippet()
                    : "";
            return s;
        }

        GraphNode toGraphNode() {
            NodeKind nodeKind = NodeKind.valueOf(kind);
            String src = sourceSnippet != null ? sourceSnippet : "";
            String decl = declarationSnippet != null ? declarationSnippet : "";
            return new GraphNodeImpl(
                    id,
                    nodeKind,
                    simpleName,
                    filePath,
                    startLine,
                    endLine,
                    src,
                    new GraphNodeDeclaration(id, nodeKind, simpleName, filePath, startLine, endLine, decl)
            );
        }
    }

    public static final class EdgeSnapshot {
        public String caller;
        public String kind;
        public String callee;
        public String filePath;
        public int startLine;
        public int endLine;

        static EdgeSnapshot from(GraphEdge e) {
            EdgeSnapshot s = new EdgeSnapshot();
            s.caller = e.caller();
            s.kind = e.kind().name();
            s.callee = e.callee();
            s.filePath = e.filePath();
            s.startLine = e.startLine();
            s.endLine = e.endLine();
            return s;
        }

        GraphEdge toGraphEdge() {
            return new GraphEdge(
                    caller,
                    EdgeKind.valueOf(kind),
                    callee,
                    filePath != null ? filePath : "",
                    startLine,
                    endLine
            );
        }
    }
}
