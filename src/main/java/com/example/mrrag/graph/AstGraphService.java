package com.example.mrrag.graph;

import com.example.mrrag.app.source.LocalCloneProjectSourceProvider;
import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.raw.ProjectKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Spring façade over {@link GraphBuilderImpl}: project-key helpers, cache delegation,
 * and path normalization for review/diff flows. All graph data types live on
 * {@link GraphBuilderImpl} ({@link GraphNode}, {@link ProjectGraph}, …).
 *
 * <p>{@link ProjectKey} is now created by the {@link ProjectSourceProvider} itself
 * (via {@link ProjectSourceProvider#projectKey()}). For callers that only have a
 * {@link Path}, a temporary {@link LocalCloneProjectSourceProvider} is used to
 * produce the key — no duplication of fingerprint logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AstGraphService {

    private final GraphBuilderImpl delegate;

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /** Build (or return cached) graph for a local project directory. */
    public ProjectGraph buildGraph(Path projectRoot) throws Exception {
        return delegate.buildGraph(new LocalCloneProjectSourceProvider(projectRoot));
    }

    /** Build (or return cached) graph using an arbitrary source provider. */
    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        return delegate.buildGraph(provider);
    }

    // ------------------------------------------------------------------
    // ProjectKey helpers
    // ------------------------------------------------------------------

    /**
     * Returns the cache key for a local project directory.
     * Equivalent to {@code new LocalCloneProjectSourceProvider(root).projectKey()}.
     */
    public ProjectKey projectKey(Path projectRoot) {
        return new LocalCloneProjectSourceProvider(projectRoot).projectKey();
    }

    // ------------------------------------------------------------------
    // Invalidation
    // ------------------------------------------------------------------

    public void invalidate(ProjectSourceProvider provider) {
        delegate.invalidate(provider);
    }

    public void invalidate(ProjectKey key) {
        delegate.invalidate(key);
    }

    public void invalidate(Path projectRoot) {
        delegate.invalidate(projectRoot);
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    public String normalizeFilePath(String diffPath, ProjectGraph graph) {
        return delegate.normalizeFilePath(diffPath, graph);
    }

    /** Direct access to the raw builder (for callers in graph/app layer). */
    public GraphBuilderImpl rawBuilder() {
        return delegate;
    }

    /** Same as {@link #buildGraph(ProjectSourceProvider)}; kept for named call-sites. */
    public ProjectGraph buildRawGraph(ProjectSourceProvider provider) throws Exception {
        return delegate.buildGraph(provider);
    }
}
