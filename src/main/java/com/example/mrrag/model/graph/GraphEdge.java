package com.example.mrrag.model.graph;

/**
 * A directed edge in the AST symbol graph.
 */
public class GraphEdge {

    public final EdgeKind  kind;
    public final GraphNode from;
    public final GraphNode to;

    public GraphEdge(EdgeKind kind, GraphNode from, GraphNode to) {
        this.kind = kind;
        this.from = from;
        this.to   = to;
    }

    @Override
    public String toString() {
        return from.id + " --[" + kind + "]--> " + to.id;
    }
}
