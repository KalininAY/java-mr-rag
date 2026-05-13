package com.example.mrrag.graph.cache;

import com.example.mrrag.graph.model.ProjectGraph;

/**
 * A {@link ProjectGraph} pinned to the commit SHA at which it was built
 * (or last patched).
 *
 * <p>Immutable carrier — when a patch is applied the registry replaces
 * the whole record with a new {@code VersionedGraph} that has the updated SHA.
 */
public record VersionedGraph(String commitSha, ProjectGraph graph) {

    public VersionedGraph {
        if (commitSha == null || commitSha.isBlank())
            throw new IllegalArgumentException("commitSha must not be blank");
        if (graph == null)
            throw new IllegalArgumentException("graph must not be null");
    }
}
