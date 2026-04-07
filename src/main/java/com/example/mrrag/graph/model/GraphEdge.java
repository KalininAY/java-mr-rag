package com.example.mrrag.graph.model;

public record GraphEdge(
        String caller, EdgeKind kind, String callee,
        String filePath, int line) {
}
