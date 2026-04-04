package com.example.mrrag.service;

import com.example.mrrag.service.AstGraphService.ProjectGraph;
import com.example.mrrag.service.source.ProjectSourceProvider;

/**
 * Primary abstraction for building an AST symbol graph from a Java project.
 *
 * <p>Decouples graph construction from the mechanism used to obtain source files.
 * The source files are supplied by a {@link ProjectSourceProvider} implementation:
 * <ul>
 *   <li>{@link com.example.mrrag.service.source.GitLabProjectSourceProvider} — fetches
 *       files directly from the GitLab API (no {@code git clone} required).</li>
 *   <li>{@link com.example.mrrag.service.source.LocalCloneProjectSourceProvider} — reads
 *       files from a locally cloned repository directory.</li>
 * </ul>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * // GitLab (no clone)
 * ProjectSourceProvider provider =
 *     new GitLabProjectSourceProvider(gitLabApi, projectId, "main");
 * ProjectGraph graph = graphBuildService.buildGraph(provider);
 *
 * // Local clone
 * ProjectSourceProvider provider =
 *     new LocalCloneProjectSourceProvider(Path.of("/workspace/my-repo"));
 * ProjectGraph graph = graphBuildService.buildGraph(provider);
 * }</pre>
 */
public interface GraphBuildService {

    /**
     * Builds (or returns a cached) symbol graph for the project described by
     * {@code sourceProvider}.
     *
     * <p>Caching is keyed on {@link ProjectSourceProvider#projectKey()}. Pass a provider
     * whose key changes on every call (e.g. includes a commit SHA) to bypass the cache.
     *
     * @param sourceProvider supplies the Java source files of the project
     * @return fully populated (or partial, on parse errors) project graph
     * @throws Exception if source loading or model building fails unrecoverably
     */
    ProjectGraph buildGraph(ProjectSourceProvider sourceProvider) throws Exception;

    /**
     * Normalises a path coming from a GitLab diff into the path stored inside
     * the graph (handles mono-repo prefixes, cross-OS path separators, etc.).
     *
     * @param diffPath path as reported by the GitLab diff API
     * @param graph    graph to look up known paths in
     * @return matching graph-relative path, or {@code diffPath} unchanged when no match found
     */
    String normalizeFilePath(String diffPath, ProjectGraph graph);

    /**
     * Removes the cached graph for the given project key.
     *
     * @param projectKey value returned by {@link ProjectSourceProvider#projectKey()}
     */
    void invalidate(Object projectKey);
}
