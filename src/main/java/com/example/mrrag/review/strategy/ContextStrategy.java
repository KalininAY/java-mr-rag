package com.example.mrrag.review.strategy;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangeGroup;
import com.example.mrrag.review.model.ChangeType;
import com.example.mrrag.review.model.EnrichmentSnippet;

import java.nio.file.Path;
import java.util.List;

/**
 * Strategy that resolves context snippets for a specific {@link ChangeType}.
 *
 * <p>Implementations are discovered via Spring's {@code @Component} scanning
 * and injected as {@code List<ContextStrategy>} into {@link com.example.mrrag.review.pipeline.RagPipeline}.
 */
public interface ContextStrategy {

    /** Returns {@code true} if this strategy handles the given change type. */
    boolean supports(ChangeType changeType);

    /**
     * Collect context snippets for a single group.
     *
     * <p>Implementations must be idempotent and must never throw —
     * log warnings and return an empty list on error.
     *
     * @param group        the change group to enrich
     * @param sourceGraph  AST graph of the source (feature) branch
     * @param targetGraph  AST graph of the target (base) branch
     * @param sourceRepoDir local path to the cloned source branch
     * @param targetRepoDir local path to the cloned target branch
     * @return list of collected context snippets (may be empty, never null)
     */
    List<EnrichmentSnippet> collectContext(
            ChangeGroup group,
            ProjectGraph sourceGraph,
            ProjectGraph targetGraph,
            Path sourceRepoDir,
            Path targetRepoDir
    );
}
