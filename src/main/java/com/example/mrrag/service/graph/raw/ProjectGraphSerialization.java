package com.example.mrrag.service.graph.raw;

import com.example.mrrag.service.AstGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON snapshot of a {@link AstGraphService.ProjectGraph} for disk cache.
 */
public final class ProjectGraphSerialization {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private ProjectGraphSerialization() {
    }

    public static byte[] toJson(AstGraphService.ProjectGraph graph) throws IOException {
        Snapshot snap = Snapshot.from(graph);
        return MAPPER.writeValueAsBytes(snap);
    }

    public static AstGraphService.ProjectGraph fromJson(byte[] json) throws IOException {
        Snapshot snap = MAPPER.readValue(json, Snapshot.class);
        return snap.toGraph();
    }

    public static void write(AstGraphService.ProjectGraph graph, OutputStream out) throws IOException {
        Snapshot snap = Snapshot.from(graph);
        MAPPER.writeValue(out, snap);
    }

    public static AstGraphService.ProjectGraph read(InputStream in) throws IOException {
        Snapshot snap = MAPPER.readValue(in, Snapshot.class);
        return snap.toGraph();
    }

    public static final class Snapshot {
        public List<NodeSnapshot> nodes;
        public List<EdgeSnapshot> edges;

        public Snapshot() {}

        Snapshot(List<NodeSnapshot> nodes, List<EdgeSnapshot> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        static Snapshot from(AstGraphService.ProjectGraph g) {
            List<NodeSnapshot> nodes = new ArrayList<>(g.nodes.size());
            for (AstGraphService.GraphNode n : g.nodes.values()) {
                nodes.add(NodeSnapshot.from(n));
            }
            List<EdgeSnapshot> edges = new ArrayList<>();
            for (List<AstGraphService.GraphEdge> list : g.edgesFrom.values()) {
                for (AstGraphService.GraphEdge e : list) {
                    edges.add(EdgeSnapshot.from(e));
                }
            }
            return new Snapshot(nodes, edges);
        }

        AstGraphService.ProjectGraph toGraph() {
            List<NodeSnapshot> nList = nodes != null ? nodes : List.of();
            List<EdgeSnapshot> eList = edges != null ? edges : List.of();
            List<AstGraphService.GraphNode> nodeList = new ArrayList<>();
            for (NodeSnapshot n : nList) nodeList.add(n.toGraphNode());
            List<AstGraphService.GraphEdge> edgeList = new ArrayList<>();
            for (EdgeSnapshot e : eList) edgeList.add(e.toGraphEdge());
            return AstGraphService.ProjectGraph.reconstruct(nodeList, edgeList);
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

        static NodeSnapshot from(AstGraphService.GraphNode n) {
            NodeSnapshot s = new NodeSnapshot();
            s.id = n.id();
            s.kind = n.kind().name();
            s.simpleName = n.simpleName();
            s.filePath = n.filePath();
            s.startLine = n.startLine();
            s.endLine = n.endLine();
            s.sourceSnippet = n.sourceSnippet();
            s.declarationSnippet = n.declarationSnippet();
            return s;
        }

        AstGraphService.GraphNode toGraphNode() {
            return new AstGraphService.GraphNode(
                    id, AstGraphService.NodeKind.valueOf(kind),
                    simpleName, filePath, startLine, endLine,
                    sourceSnippet != null ? sourceSnippet : "",
                    declarationSnippet != null ? declarationSnippet : ""
            );
        }
    }

    public static final class EdgeSnapshot {
        public String caller;
        public String kind;
        public String callee;
        public String filePath;
        public int line;

        static EdgeSnapshot from(AstGraphService.GraphEdge e) {
            EdgeSnapshot s = new EdgeSnapshot();
            s.caller = e.caller();
            s.kind = e.kind().name();
            s.callee = e.callee();
            s.filePath = e.filePath();
            s.line = e.line();
            return s;
        }

        AstGraphService.GraphEdge toGraphEdge() {
            return new AstGraphService.GraphEdge(
                    caller, AstGraphService.EdgeKind.valueOf(kind),
                    callee, filePath != null ? filePath : "", line
            );
        }
    }
}
