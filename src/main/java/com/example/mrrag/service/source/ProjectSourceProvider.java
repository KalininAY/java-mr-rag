package com.example.mrrag.service.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Strategy: supply the raw Java source files of a project.
 *
 * <p>Implementations are responsible only for <em>obtaining</em> the files;
 * all AST analysis is performed by
 * {@link com.example.mrrag.service.graph.GraphBuildService}.
 *
 * <h2>Built-in implementations</h2>
 * <ul>
 *   <li>{@link LocalCloneProjectSourceProvider} — reads files from a locally
 *       cloned directory on the file-system.</li>
 *   <li>{@link GitLabProjectSourceProvider} — fetches files via the GitLab
 *       Repositories API without cloning the project.</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Must never return {@code null} — return an empty list instead.</li>
 *   <li>Each {@link ProjectSource} must have a non-blank, repository-relative
 *       {@code path} (e.g. {@code "src/main/java/com/example/Foo.java"}).
 *       This path is used directly as {@code GraphNode.filePath()} and must
 *       therefore match the paths that appear in GitLab diff output.</li>
 *   <li>Implementations are expected to be stateless and created per-request.
 *       Caching, if needed, belongs to the caller.</li>
 * </ul>
 */
public interface ProjectSourceProvider {

    /**
     * Load all Java source files and return them as in-memory records.
     *
     * @return non-null list of sources; may be empty
     * @throws Exception on any IO / API error
     */
    List<ProjectSource> getSources() throws Exception;

    /**
     * Returns a stable, human-readable identifier for this project snapshot.
     *
     * <p>Used as cache key by the graph layer so the same project is never
     * analysed twice within a single JVM lifetime.  Format conventions:
     * <ul>
     *   <li>GitLab API: {@code "gitlab:<numericId>@<ref>"} —
     *       e.g. {@code "gitlab:123@main"} or {@code "gitlab:123@a1b2c3d4"}</li>
     *   <li>Local clone: {@code "local:<absolutePath>"} —
     *       e.g. {@code "local:/tmp/repo-clone"}</li>
     * </ul>
     *
     * <p>The default implementation returns an empty string, which effectively
     * disables caching — override in every concrete provider.
     *
     * @return non-null project identifier; may be empty to disable caching
     */
    default String projectId() {
        return "";
    }

    /**
     * When sources come from a local checkout, returns the repository root so
     * the graph layer can resolve compile classpath (Gradle/Maven).
     * API-only providers return empty.
     */
    default Optional<Path> localProjectRoot() {
        return Optional.empty();
    }
}
