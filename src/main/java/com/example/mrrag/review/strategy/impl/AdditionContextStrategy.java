package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects context for ADD lines in a {@link UnionLine}.
 *
 * <p>Iterates {@link UnionLine#graphNodes()} directly — these are the AST nodes
 * already resolved by {@link com.example.mrrag.review.pipeline.AstChangeGrouper},
 * no secondary {@code nodesAtLine()} lookup needed.
 *
 * <p>Only processes nodes whose {@link UnionLine#nodeOrigins()} contain at least
 * one ADD line — pure DELETE nodes are skipped.
 *
 * <p>For each qualifying node follows outgoing edges in {@code sourceGraph} whose
 * {@code startLine} matches one of the ADD origin lines. Edges with {@code startLine == 0}
 * (synthetic / unresolved) are always included as a safety fallback.
 */
@Slf4j
@Component
public class AdditionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        if (union.graphNodes().isEmpty()) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();
        Set<String> seenDecl = new HashSet<>();

        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            // skip nodes with no ADD origin — pure deletions
            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            // line numbers of ADD-type origins for this node
            Set<Integer> addLines = origins.stream()
                    .filter(l -> l.type() == ChangedLine.LineType.ADD)
                    .map(l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber())
                    .collect(Collectors.toSet());

            for (GraphEdge edge : sourceGraph.outgoing(node.id())) {
                if (snippets.size() >= maxSnippetsPerGroup) break;

                // only follow edges that originate from an ADD line;
                // edges with startLine == 0 (synthetic) are let through as fallback
                if (edge.startLine() > 0 && !addLines.contains(edge.startLine())) continue;

                String targetId = edge.callee();
                if (seenDecl.contains(targetId)) continue;
                seenDecl.add(targetId);

                GraphNode target = sourceGraph.nodes.get(targetId);
                if (target == null) continue;

                switch (edge.kind()) {
                    case INVOKES -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, target,
                            "Declaration of method '" + target.simpleName() + "' called in added code"));
                    case READS_FIELD, WRITES_FIELD -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.FIELD_DECLARATION, target,
                            "Declaration of field '" + target.simpleName() + "' accessed in added code"));
                    case READS_LOCAL_VAR, WRITES_LOCAL_VAR -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION, target,
                            "Declaration of variable '" + target.simpleName() + "' used in added code"));
                    default -> { }
                }
            }
        }

        log.debug("AdditionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return filterAlreadyInDiff(union, snippets);
    }
}
