package com.example.mrrag.graph.model;

/**
 * Immutable node in the project symbol graph
 */
public interface GraphNode {
    String getId();
    NodeKind getKind();
    String getSimpleName();
    String getFilePath();
    int getStartLine();
    int getEndLine();
    String getSourceSnippet();

}
