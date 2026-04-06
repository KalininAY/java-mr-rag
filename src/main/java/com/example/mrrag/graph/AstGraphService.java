package com.example.mrrag.graph;

import com.example.mrrag.commons.source.ProjectSourceProvider;
import com.example.mrrag.graph.GraphRawBuilder.ProjectGraphRaw;
import com.example.mrrag.graph.raw.ProjectKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Spring façade over {@link GraphRawBuilder}: project-key helpers, cache delegation,
 * and path normalization for review/diff flows. All graph data types live on
 * {@link GraphRawBuilder} ({@link GraphRawBuilder.GraphNode}, {@link GraphRawBuilder.ProjectGraphRaw}, …).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AstGraphService {

    private final GraphRawBuilder delegate;

    public ProjectGraphRaw buildGraph(Path projectRoot) throws Exception {
        ProjectKey key = delegate.projectKey(projectRoot);
        return delegate.buildGraph(key);
    }

    public ProjectGraphRaw buildGraph(ProjectKey key) throws Exception {
        return delegate.buildGraph(key);
    }

    public ProjectGraphRaw buildGraph(ProjectSourceProvider provider) throws Exception {
        return delegate.buildGraph(provider);
    }

    public ProjectKey projectKey(Path projectRoot) {
        return delegate.projectKey(projectRoot);
    }

    public void invalidate(ProjectKey key) {
        delegate.invalidate(key);
    }

    public void invalidate(Path projectRoot) {
        delegate.invalidate(projectRoot);
    }

    public String normalizeFilePath(String diffPath, ProjectGraphRaw graph) {
        return delegate.normalizeFilePath(diffPath, graph);
    }

    /** Direct access to the raw builder (for callers in graph/app layer). */
    public GraphRawBuilder rawBuilder() {
        return delegate;
    }

    /** Same as {@link #buildGraph(ProjectSourceProvider)}; kept for named call-sites. */
    public ProjectGraphRaw buildRawGraph(ProjectSourceProvider provider) throws Exception {
        return delegate.buildGraph(provider);
    }
}
