package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Collects context when a {@link UnionLine} contains both ADD and DELETE lines,
 * or spans multiple files (CROSS_SCOPE).
 *
 * <p>Picks METHOD nodes directly from {@link UnionLine#graphNodes()} — no
 * secondary {@code nodesAtLine()} lookup. Only nodes touched by both ADD and
 * DELETE (i.e. truly modified) get a full METHOD_BODY snippet.
 *
 * <p>Then delegates to {@link AdditionContextStrategy} and
 * {@link DeletionContextStrategy} for cross-reference snippets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModificationContextStrategy implements ContextStrategy {

    private final AdditionContextStrategy additionStrategy;
    private final DeletionContextStrategy deletionStrategy;

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        boolean hasAdd = union.changedLines().stream()
                .anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
        boolean hasDel = union.changedLines().stream()
                .anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
        boolean multiFile = union.changedLines().stream()
                .map(ChangedLine::filePath).distinct().count() > 1;

        if (!((hasAdd && hasDel) || multiFile)) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();

        // 1. METHOD_BODY for nodes touched by BOTH add and delete (modified in place)
        collectModifiedMethodBodies(union, sourceGraph, snippets);

        // 2. Cross-reference snippets via sub-strategies
        if (snippets.size() < maxSnippetsPerGroup) {
            additionStrategy.collectContext(union, sourceGraph, targetGraph).stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }
        if (snippets.size() < maxSnippetsPerGroup) {
            deletionStrategy.collectContext(union, sourceGraph, targetGraph).stream()
                    .limit(maxSnippetsPerGroup - snippets.size())
                    .forEach(snippets::add);
        }

        log.debug("ModificationContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return filterAlreadyInDiff(union, snippets);
    }

    /**
     * Finds METHOD nodes in {@link UnionLine#graphNodes()} whose
     * {@link UnionLine#nodeOrigins()} contain both ADD and DELETE lines —
     * these are truly modified methods, not just added or deleted ones.
     */
    private void collectModifiedMethodBodies(
            UnionLine union, ProjectGraph sourceGraph, List<EnrichmentSnippet> snippets) {

        Set<String> seen = new LinkedHashSet<>();

        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            if (node.kind() != NodeKind.METHOD) continue;
            if (!seen.add(node.id())) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasAdd || !hasDel) continue; // not modified in place

            // prefer sourceGraph (new version); fall back to targetGraph
            GraphNode resolved = sourceGraph.nodes.getOrDefault(node.id(),
                    targetGraph -> targetGraph.nodes.get(node.id()));
            if (resolved == null) resolved = node;

            int end = Math.min(resolved.endLine(), resolved.startLine() + maxSnippetLines - 1);
            snippets.add(new EnrichmentSnippet(
                    EnrichmentSnippet.SnippetType.METHOD_BODY,
                    resolved.filePath(), resolved.startLine(), end,
                    resolved.simpleName(),
                    resolved.sourceSnippet(),
                    "Body of modified method '" + resolved.simpleName() + "'"
            ));
        }
    }
}
