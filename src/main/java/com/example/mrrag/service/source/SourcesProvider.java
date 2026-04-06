package com.example.mrrag.service.source;

import com.example.mrrag.service.dto.ProjectSourceDto;

/**
 * Strategy interface for creating a {@link ProjectSourceDto} needed to build
 * an AST symbol graph.
 *
 * <p>Implementations hide the origin of the project sources — whether they
 * come from a local file-system clone or from a remote API (e.g. GitLab) —
 * and produce a uniform DTO that can be passed directly to
 * {@link com.example.mrrag.service.graph.AstGraphI#buildGraph(ProjectSourceDto)}.
 *
 * <h2>Built-in implementations</h2>
 * <ul>
 *   <li>{@link LocalCloneSourcesProvider} — reads {@code .java} files from a
 *       locally cloned directory and sets {@code classpathRoot}.</li>
 *   <li>{@link GitLabSourcesProvider} — fetches {@code .java} files from the
 *       GitLab Repositories API without cloning; {@code classpathRoot} is
 *       {@code null}.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Never return {@code null} — return a DTO with an empty source list instead.</li>
 *   <li>Each {@link ProjectSource} must have a non-blank, repository-relative
 *       {@code path} (e.g. {@code "src/main/java/com/example/Foo.java"}).</li>
 *   <li>Implementations are expected to be stateless and created per-request.</li>
 * </ul>
 */
public interface SourcesProvider {

    /**
     * Load all Java source files and return them wrapped in a {@link ProjectSourceDto}.
     *
     * @return non-null DTO; sources list may be empty but must not be null
     * @throws Exception on any IO / API error
     */
    ProjectSourceDto getProjectSourceDto() throws Exception;
}
