package com.example.mrrag.app.source;

import com.example.mrrag.graph.raw.ProjectFingerprint;
import com.example.mrrag.graph.raw.ProjectKey;

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

    /**
     * Returns a stable cache key that uniquely identifies this project <em>version</em>.
     *
     * <p>The default implementation covers the most common case:
     * a local checkout where {@link #localProjectRoot()} is present.
     * Fingerprint is computed via {@link ProjectFingerprint#compute(Path)} —
     * it prefers the git HEAD SHA and falls back to a content hash of build files.
     *
     * <p>Providers that source code remotely (e.g. directly from GitLab API)
     * <strong>must override</strong> this method and return a key whose
     * {@code fingerprint} reflects the exact ref/commit being analysed,
     * e.g. {@code new ProjectKey(virtualRoot, "git:" + commitSha)}.
     *
     * @return a {@link ProjectKey} that can be used as a map/cache key
     * @throws IllegalStateException if the provider cannot produce a key
     *         (e.g. a purely in-memory provider with no stable identity)
     */
    default ProjectKey projectKey() {
        return localProjectRoot()
                .map(root -> new ProjectKey(root, ProjectFingerprint.compute(root)))
                .orElseThrow(() -> new IllegalStateException(
                        "Provider " + getClass().getSimpleName() +
                        " has no localProjectRoot — override projectKey() explicitly"));
    }
}
