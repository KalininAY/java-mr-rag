package com.example.mrrag.graph.raw.source;

import com.example.mrrag.graph.GraphBuilder;

import java.util.List;

/**
 * Strategy: supply the raw Java source files of a project.
 *
 * <p>Implementations are responsible only for <em>obtaining</em> the files;
 * all AST analysis is performed by
 * {@link GraphBuilder}.
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
}
