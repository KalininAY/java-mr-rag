package com.example.mrrag.model.graph;

/**
 * A node in the symbol graph.
 *
 * @param id            unique node identifier
 * @param kind          {@link NodeKind} of this node
 * @param simpleName    unqualified name
 * @param filePath      source-relative file path ({@code "unknown"} for external nodes)
 * @param startLine     first source line (1-based); {@code -1} for external/synthetic nodes
 *                      that have no source file in this project
 * @param endLine       last source line (1-based); {@code -1} for external/synthetic nodes
 * @param sourceSnippet verbatim original source lines for project nodes;
 *                      Spoon pretty-printed text for external/synthetic nodes
 *                      (no source file available); empty string when unavailable
 */
public record GraphNode(
        String id,
        NodeKind kind,
        String simpleName,
        String filePath,
        int startLine,
        int endLine,
        String sourceSnippet
) {
}
