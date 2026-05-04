package com.example.mrrag.review.strategy;

import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.ChangedLine;
import com.example.mrrag.review.model.EnrichmentSnippet;
import com.example.mrrag.review.model.UnionLine;
import com.example.mrrag.review.pipeline.ContextPipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
     *
     * <p>Also removes snippets with {@code startLine <= 0} (unresolved node location)
     * and deduplicates: if a {@code CLASS_DECLARATION} snippet already exists for a given
     * {@code (filePath, startLine)}, any {@code METHOD_DECLARATION} or {@code FIELD_DECLARATION}
     * at the same location is dropped to avoid redundant context.
     */
    default List<EnrichmentSnippet> filterAlreadyInDiff(UnionLine union, List<EnrichmentSnippet> snippets) {
        if (snippets.isEmpty()) return snippets;

        // 1. Remove snippets with unresolved location
        List<EnrichmentSnippet> resolved = snippets.stream()
                .filter(s -> s.startLine() > 0)
                .collect(Collectors.toCollection(java.util.ArrayList::new));

        // 2. Remove snippets covered by the diff itself
        Set<String> diffKeys = new HashSet<>();
        for (ChangedLine l : union.changedLines()) {
            if (l.type() == ChangedLine.LineType.CONTEXT) continue;
            int lineNo = l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber();
            if (lineNo > 0) diffKeys.add(l.filePath() + ":" + lineNo);
        }
        resolved.removeIf(s -> diffKeys.contains(s.filePath() + ":" + s.startLine()));

        // 3. Drop METHOD_DECLARATION / FIELD_DECLARATION when CLASS_DECLARATION exists
        //    at the same (filePath, startLine) — avoids duplicate context for constructors
        //    resolved via INSTANTIATES that point to the class node itself.
        Set<String> classKeys = resolved.stream()
                .filter(s -> s.type() == EnrichmentSnippet.SnippetType.CLASS_DECLARATION)
                .map(s -> s.filePath() + ":" + s.startLine())
                .collect(Collectors.toSet());

        resolved.removeIf(s ->
                (s.type() == EnrichmentSnippet.SnippetType.METHOD_DECLARATION
                        || s.type() == EnrichmentSnippet.SnippetType.FIELD_DECLARATION)
                        && classKeys.contains(s.filePath() + ":" + s.startLine()));

        return resolved;
    }
}
