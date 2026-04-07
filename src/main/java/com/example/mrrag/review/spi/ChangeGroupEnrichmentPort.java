package com.example.mrrag.review.spi;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangeGroup;

import java.nio.file.Path;
import java.util.List;

/**
 * Enriches change groups with contextual snippets using the AST graph.
 * Implemented in {@code app} layer by
 * {@link com.example.mrrag.app.service.ContextEnricher}.
 */
public interface ChangeGroupEnrichmentPort {

    List<ChangeGroup> enrich(
            List<ChangeGroup> groups,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph,
            Path sourceRepoDir,
            Path targetRepoDir) throws Exception;
}
