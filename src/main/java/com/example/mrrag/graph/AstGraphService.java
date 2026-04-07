package com.example.mrrag.graph;

import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.raw.ProjectKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spring façade over {@link GraphBuilderImpl}.
 *
 * <p>The single entry point for graph construction is
 * {@link #buildGraph(ProjectSourceProvider)} — callers are responsible for
 * instantiating the appropriate {@link ProjectSourceProvider} (e.g.
 * {@code new LocalCloneProjectSourceProvider(path)} or
 * {@code new GitLabProjectSourceProvider(api, id, ref)}).
 * The provider both supplies the source files <em>and</em> owns the
 * {@link ProjectKey} that drives cache lookups.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AstGraphService {

    private final GraphBuilderImpl delegate;

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /**
     * Build (or return cached) {@link ProjectGraph} for the given provider.
     * The cache key is obtained from {@link ProjectSourceProvider#projectKey()}.
     */
    public ProjectGraph buildGraph(ProjectSourceProvider provider) throws Exception {
        return delegate.buildGraph(provider);
    }

    // ------------------------------------------------------------------
    // Invalidation
    // ------------------------------------------------------------------

    /** Evict all caches for the given provider (uses {@link ProjectSourceProvider#projectKey()}). */
    public void invalidate(ProjectSourceProvider provider) {
        delegate.invalidate(provider);
    }

    /** Evict all caches by explicit key (e.g. after a key was stored separately). */
    public void invalidate(ProjectKey key) {
        delegate.invalidate(key);
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
