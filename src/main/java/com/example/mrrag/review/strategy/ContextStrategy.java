package com.example.mrrag.review.strategy;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangeGroup;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.ChangeType;
import com.example.mrrag.review.model.EnrichmentSnippet;
import com.example.mrrag.review.pipeline.ContextPipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strategy that resolves context snippets for a specific {@link ChangeType}.
 *
 * <p>Implementations are discovered via Spring's {@code @Component} scanning
 * and injected as {@code List<ContextStrategy>} into {@link ContextPipeline}.
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
     * @return list of collected context snippets (may be empty, never null)
     */
    List<EnrichmentSnippet> collectContext(ChangeGroup group, ProjectGraph sourceGraph, ProjectGraph targetGraph);

    /**
     * Removes snippets whose {@code (filePath, startLine)} is already covered by
     * an ADD or DELETE line in the group diff.
     *
     * <p>Call this as the final step inside {@link #collectContext} before
     * returning, so that snippets containing content already visible in the
     * diff are not duplicated in the output.
     *
     * <pre>{@code
     * return filterAlreadyInDiff(group, snippets);
     * }</pre>
     */
    default List<EnrichmentSnippet> filterAlreadyInDiff(ChangeGroup group, List<EnrichmentSnippet> snippets) {
        if (snippets.isEmpty()) return snippets;

        Set<String> diffKeys = new HashSet<>();
        for (ChangedLine l : group.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            int lineNo = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
            if (lineNo > 0) diffKeys.add(l.filePath() + ":" + lineNo);
        }

        return snippets.stream()
                .filter(s -> !diffKeys.contains(s.filePath() + ":" + s.startLine()))
                .toList();
    }
}
