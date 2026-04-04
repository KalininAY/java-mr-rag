package com.example.mrrag.service.source;

import java.util.List;

/**
 * Strategy interface for supplying Java source files of a project to the graph builder.
 *
 * <p>Two built-in implementations are provided:
 * <ul>
 *   <li>{@link GitLabProjectSourceProvider} — downloads {@code .java} files directly from
 *       the GitLab API using a project id and a ref (branch / tag / commit SHA).
 *       No {@code git clone} is performed.</li>
 *   <li>{@link LocalCloneProjectSourceProvider} — reads {@code .java} files from a locally
 *       cloned repository directory using {@link java.nio.file.Files#walk}.</li>
 * </ul>
 *
 * <h2>Adding a custom implementation</h2>
 * <pre>{@code
 * public class S3SourceProvider implements ProjectSourceProvider {
 *
 *     public Object projectKey() { return "s3://" + bucket + "/" + prefix; }
 *
 *     public List<VirtualSource> loadSources() throws Exception {
 *         // download .java files from S3 ...
 *     }
 *
 *     public String rootHint() { return ""; }
 * }
 * }</pre>
 */
public interface ProjectSourceProvider {

    /**
     * Unique, stable key used to cache the resulting graph.
     *
     * <p>Two calls with the same logical source set must return equal keys so that
     * {@link com.example.mrrag.service.GraphBuildService#buildGraph} can return the
     * cached result.  Return a value that changes whenever the source changes
     * (e.g. include the commit SHA) to force a fresh build.
     *
     * @return non-null cache key (may be a {@link java.nio.file.Path}, a {@link String}, etc.)
     */
    Object projectKey();

    /**
     * Loads all Java source files of the project.
     *
     * @return non-null, possibly empty list of in-memory source records
     * @throws Exception on any IO / API error
     */
    List<VirtualSource> loadSources() throws Exception;

    /**
     * Hint for path normalisation inside the graph builder.
     *
     * <ul>
     *   <li>For local clones: the absolute path of the repository root
     *       (e.g. {@code "/tmp/workspace/my-repo"}).</li>
     *   <li>For API-based providers (GitLab, S3, …): empty string, because
     *       paths are already repo-relative.</li>
     * </ul>
     */
    String rootHint();
}
