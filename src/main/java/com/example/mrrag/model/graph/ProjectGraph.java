package com.example.mrrag.model.graph;

import java.util.*;

/**
 * The full in-memory AST symbol graph for a project.
 *
 * <p>{@code byFile} maps a source-file path (relative to project root) to
 * the list of top-level classes declared in that file.
 */
public class ProjectGraph {

    public final Map<String, List<GraphNode>> byFile = new LinkedHashMap<>();
    public final List<GraphEdge>              edges  = new ArrayList<>();

    /** Returns the set of all known source-file paths in this graph. */
    public Set<String> allFilePaths() {
        return byFile.keySet();
    }
}
