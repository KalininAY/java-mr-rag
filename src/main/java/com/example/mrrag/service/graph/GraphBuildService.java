package com.example.mrrag.service.graph;

import com.example.mrrag.service.source.ProjectSourceProvider;
import com.example.mrrag.service.AstGraphService.ProjectGraph;

import java.nio.file.Path;

/**
 * Primary contract for building an AST symbol graph.
 *
 * <p>Implementations receive Java source files through a
 * {@link ProjectSourceProvider} and must not care about how those files
 * are obtained (local clone, GitLab API, test fixtures, etc.).
 *
 * <h2>Two build modes</h2>
 * <ul>
 *   <li>{@link #buildGraph(ProjectSourceProvider)} — generic, provider-agnostic path.
 *       Preferred for all new callers.</li>
 *   <li>{@link #buildGraph(Path)} — legacy local-clone shortcut kept for backward
 *       compatibility with existing callers that pass a cloned directory.</li>
 * </ul>
 */
public interface GraphBuildService {

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
     * Build (or return a cached) symbol graph from a locally cloned directory.
     *
     * <p>This is a convenience overload that internally wraps the path in a
     * {@link com.example.mrrag.service.source.LocalCloneProjectSourceProvider}
     * and delegates to {@link #buildGraph(ProjectSourceProvider)}.
     *
     * @param projectRoot absolute path of the cloned repository root
     * @return fully populated (or partial) {@link ProjectGraph}
     * @deprecated Prefer {@link #buildGraph(ProjectSourceProvider)} with an
     *     explicit {@link com.example.mrrag.service.source.LocalCloneProjectSourceProvider}.
     */
    @Deprecated
    ProjectGraph buildGraph(Path projectRoot);

    /**
     * Evict any cached graph for the given local clone directory.
     *
     * @param projectRoot the clone root previously passed to {@link #buildGraph(Path)}
     */
    void invalidate(Path projectRoot);

    /**
     * Translate a path from a GitLab diff into the graph-relative path.
     *
     * <p>Handles mono-repo prefixes, back-slash normalisation, and partial
     * suffix matching against all paths stored in {@code graph}.
     *
     * @param diffPath path as reported by GitLab
     * @param graph    the target graph whose known paths are used for matching
     * @return matching graph-relative path, or {@code diffPath} unchanged when
     *         no match is found
     */
    String normalizeFilePath(String diffPath, ProjectGraph graph);
}
