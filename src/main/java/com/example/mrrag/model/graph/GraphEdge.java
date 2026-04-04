package com.example.mrrag.model.graph;

/**
 * A directed, typed edge between two graph nodes.
 */
public record GraphEdge(
        String caller,
        EdgeKind kind,
        String callee,
        String filePath,
        int line
) {
}
