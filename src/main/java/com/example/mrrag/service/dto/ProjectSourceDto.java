package com.example.mrrag.service.dto;

import com.example.mrrag.service.source.ProjectSource;

import java.nio.file.Path;
import java.util.List;

/**
 * DTO that carries all data required to build an AST symbol graph for a project.
 *
 * <p>This class is the common input for both local-clone projects and GitLab projects,
 * allowing graph-building logic ({@link com.example.mrrag.service.graph.AstGraphI}) to
 * remain agnostic to the project origin.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #projectId} — human-readable identifier (e.g. "gitlab:123@main" or
 *       a local path slug). Used for cache keys and logging.</li>
 *   <li>{@link #sources} — list of in-memory Java source files to analyse.</li>
 *   <li>{@link #classpathRoot} — optional path of the local project root;
 *       when non-null the graph builder attempts to resolve Spoon classpath
 *       from Gradle / Maven build descriptors. Pass {@code null} for GitLab
 *       (API-based) projects.</li>
 * </ul>
 *
 * <p>Instances are created by implementations of
 * {@link com.example.mrrag.service.source.SourcesProvider}.
 */
public record ProjectSourceDto(
        /** Unique, stable identifier for this project snapshot. */
        String projectId,

        /** All {@code .java} source files that should be analysed. */
        List<ProjectSource> sources,

        /**
         * Absolute path of the local clone root, or {@code null} for remote
         * (API-based) projects.  When non-null the graph builder may use it
         * to locate {@code build.gradle} / {@code pom.xml} for compile classpath
         * resolution.
         */
        Path classpathRoot
) {

    /**
     * Convenience factory for API-based (no local clone) projects.
     *
     * @param projectId unique project identifier
     * @param sources   source files obtained from the remote API
     * @return DTO with {@code classpathRoot} set to {@code null}
     */
    public static ProjectSourceDto ofRemote(String projectId, List<ProjectSource> sources) {
        return new ProjectSourceDto(projectId, sources, null);
    }

    /**
     * Convenience factory for locally cloned projects.
     *
     * @param projectId    unique project identifier
     * @param sources      source files read from the local file-system
     * @param classpathRoot absolute path of the cloned repo root
     * @return DTO with all fields populated
     */
    public static ProjectSourceDto ofLocal(String projectId,
                                           List<ProjectSource> sources,
                                           Path classpathRoot) {
        return new ProjectSourceDto(projectId, sources, classpathRoot);
    }
}
