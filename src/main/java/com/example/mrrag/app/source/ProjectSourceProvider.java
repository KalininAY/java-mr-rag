package com.example.mrrag.app.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Strategy: supply the raw Java source files of a project.
 *
 * <p>Implementations are responsible only for <em>obtaining</em> the files;
 * all AST analysis is performed by the graph layer.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Must never return {@code null} — return an empty list instead.</li>
 *   <li>Each {@link ProjectSource} must have a non-blank, repository-relative
 *       {@code path} (e.g. {@code "src/main/java/com/example/Foo.java"}).</li>
 *   <li>Implementations must override {@link #projectKey()} and return a
 *       stable {@link ProjectKey} that identifies the project branch.</li>
 * </ul>
 */
public interface ProjectSourceProvider {

    /**
     * Load all Java source files and return them as in-memory records.
     *
     * @return non-null list of sources; may be empty
     */
    List<ProjectSource> getSources();

    /**
     * When sources come from a local checkout, returns the repository root so the graph
     * layer can resolve compile classpath (Gradle/Maven). Virtual/API-only providers
     * return empty.
     */
    default Optional<Path> localProjectRoot() {
        return Optional.empty();
    }

    /**
     * Returns a stable key that uniquely identifies this project branch.
     *
     * <p>All implementations must override this method.
     *
     * @return {@link ProjectKey} identifying {@code namespace/repo@branch}
     * @throws UnsupportedOperationException if not overridden
     */
    ProjectKey projectKey();
}
