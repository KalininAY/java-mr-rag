package com.example.mrrag.app.service;

import com.example.mrrag.app.source.ProjectSourceProvider;
import com.example.mrrag.graph.GraphBuilder;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.app.source.ProjectKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Spring façade over {@link GraphBuilder}.
 *
 * <p>The single entry point for graph construction is
 * {@link #buildGraph(ProjectSourceProvider, boolean)} — callers are responsible for
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

    private final GraphBuilder delegate;

    /**
     * Build (or return cached) {@link ProjectGraph} for the given provider.
     * The cache key is obtained from {@link ProjectSourceProvider#projectKey()}.
     */
    public ProjectGraph buildGraph(ProjectSourceProvider provider, boolean force) {
        return delegate.buildGraph(provider, force);
    }


    /**
     * Evict all caches by explicit key (e.g. after a key was stored separately).
     */
    public void invalidate(ProjectKey key) {
        delegate.invalidate(key);
    }
}
