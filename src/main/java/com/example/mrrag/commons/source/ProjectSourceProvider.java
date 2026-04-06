package com.example.mrrag.commons.source;

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
     * When sources come from a local checkout, returns the repository root so the graph
     * layer can resolve compile classpath (Gradle/Maven). Virtual/API-only providers
     * return empty.
     */
    default Optional<Path> localProjectRoot() {
        return Optional.empty();
    }
}
