package com.example.mrrag.review.strategy;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.EnrichmentSnippet;
import com.example.mrrag.review.model.UnionLine;
import com.example.mrrag.review.pipeline.ContextPipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strategy that collects context snippets for a single {@link UnionLine}.
 *
 * <p>Implementations are discovered via Spring's {@code @Component} scanning
 * and injected as {@code List<ContextStrategy>} into {@link ContextPipeline}.
 * Each strategy is responsible for filtering relevant lines internally.
 */
public interface ContextStrategy {

    /**
     * Collect context snippets for a single union.
     *
     * <p>Implementations must be idempotent and must never throw —
     * log warnings and return an empty list on error.
     *
     * @param union        the union of changed lines to enrich
     * @param sourceGraph  AST graph of the source (feature) branch
     * @param targetGraph  AST graph of the target (base) branch
     * @return list of collected context snippets (may be empty, never null)
     */
    List<EnrichmentSnippet> collectContext(UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph);

    /**
     * Removes snippets whose {@code (filePath, startLine)} is already covered by
     * an ADD or DELETE line in the union diff.
     */
    default List<EnrichmentSnippet> filterAlreadyInDiff(UnionLine union, List<EnrichmentSnippet> snippets) {
        if (snippets.isEmpty()) return snippets;

        Set<String> diffKeys = new HashSet<>();
        for (ChangedLine l : union.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            int lineNo = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
            if (lineNo > 0) diffKeys.add(l.filePath() + ":" + lineNo);
        }

        return snippets.stream()
                .filter(s -> !diffKeys.contains(s.filePath() + ":" + s.startLine()))
                .toList();
    }
}
