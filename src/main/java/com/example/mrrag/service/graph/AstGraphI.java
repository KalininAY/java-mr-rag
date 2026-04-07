package com.example.mrrag.service.graph;

import com.example.mrrag.model.graph.ProjectGraph;
import com.example.mrrag.service.dto.ProjectSourceDto;

/**
 * Primary contract for building an AST symbol graph.
 *
 * <p>Decouples graph construction logic from any specific source provider
 * (local clone, GitLab API, test fixtures, etc.).
 *
 * <h2>Graph model types</h2>
 * <ul>
 *   <li>{@link com.example.mrrag.model.graph.NodeKind} — CLASS, INTERFACE, METHOD, etc.</li>
 *   <li>{@link com.example.mrrag.model.graph.EdgeKind} — DECLARES, INVOKES, EXTENDS, etc.</li>
 *   <li>{@link ProjectGraph} — fully indexed graph with nodes and edges.</li>
 * </ul>
 */
public interface AstGraphI {

    /**
     * Build (or return a cached) symbol graph from a {@link ProjectSourceDto}.
     *
     * @param dto fully populated project source DTO
     * @return fully populated (or partial) {@link ProjectGraph}
     * @throws Exception on any IO / API / parse error
     */
    ProjectGraph buildGraph(ProjectSourceDto dto) throws Exception;

    /**
     * Evict any cached graph for the given project.
     *
     * @param projectId the unique project identifier previously used in
     *                  {@link ProjectSourceDto#projectId()}
     */
    void invalidate(String projectId);

    /**
     * Translate a path from a GitLab diff into the graph-relative path.
     *
     * @param diffPath path as reported by GitLab
     * @param graph    the target graph whose known paths are used for matching
     * @return matching graph-relative path, or {@code diffPath} unchanged when
     *         no match is found
     */
    String normalizeFilePath(String diffPath, ProjectGraph graph);
}
