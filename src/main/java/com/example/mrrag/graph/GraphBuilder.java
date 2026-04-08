package com.example.mrrag.graph;

import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.app.source.ProjectKey;

/**
 * Primary contract for building an AST symbol graph.
 *
 * <p>Implementations receive Java source files through a
 * {@link ProjectSourceProvider} and must not care about how those files
 * are obtained (local clone, GitLab API, test fixtures, etc.).
 */
public interface GraphBuilder {

    /**
     * Build (or return a cached) symbol graph from any source provider.
     *
     * <p>The provider abstraction means this method works identically for
     * local clones, GitLab API responses, or any future VCS backend.
     *
     * @param provider supplies the raw {@code .java} files to analyse
     * @return fully populated (or partial) {@link ProjectGraph}
     * @throws Exception on any IO / API / parse error
     */
    ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception;

    /**
     * Evict any cached graph for the given local clone directory.
     *
     * @param key the clone root previously passed to
     */
    void invalidate(ProjectKey key);

}
