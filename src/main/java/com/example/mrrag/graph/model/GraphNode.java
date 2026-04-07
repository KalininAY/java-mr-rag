package com.example.mrrag.graph.model;

public record GraphNode(
        String id, NodeKind kind, String simpleName,
        String filePath, int startLine, int endLine,
        String sourceSnippet, String declarationSnippet) {
}
