package com.example.mrrag.model.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the AST symbol graph — represents a type member or type itself.
 */
public class GraphNode {

    public final String       id;
    public final NodeKind     kind;
    public final String       name;
    public final String       qualifiedName;
    public final String       filePath;
    public final int          lineStart;
    public final int          lineEnd;
    public final List<GraphEdge> edges = new ArrayList<>();

    public GraphNode(String id, NodeKind kind, String name,
                     String qualifiedName, String filePath,
                     int lineStart, int lineEnd) {
        this.id            = id;
        this.kind          = kind;
        this.name          = name;
        this.qualifiedName = qualifiedName;
        this.filePath      = filePath;
        this.lineStart     = lineStart;
        this.lineEnd       = lineEnd;
    }

    @Override
    public String toString() {
        return kind + ":" + qualifiedName + "@" + filePath + ":" + lineStart;
    }
}
