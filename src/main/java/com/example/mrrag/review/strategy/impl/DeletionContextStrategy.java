package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.model.EdgeKind;
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
 * Collects context for DELETE lines in a {@link UnionLine}.
 *
 * <p>Iterates {@link UnionLine#graphNodes()} directly — no secondary
 * {@code byLine} lookup. Only processes nodes whose {@link UnionLine#nodeOrigins()}
 * contain at least one DELETE line.
 *
 * <p>For each qualifying node, finds incoming edges in {@code targetGraph} (the
 * base branch) to report callers/usages of deleted symbols.
 */
@Slf4j
@Component
public class DeletionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        if (union.graphNodes().isEmpty()) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();

        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            // skip nodes with no DELETE origin
            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            // look up the same node id in targetGraph (base branch)
            GraphNode targetNode = targetGraph.nodes.get(node.id());
            if (targetNode == null) continue;

            List<GraphEdge> usageEdges = targetGraph.incoming(targetNode.id()).stream()
                    .filter(e -> e.kind() != EdgeKind.DECLARES)
                    .toList();
            if (usageEdges.isEmpty()) continue;

            EnrichmentSnippet.SnippetType snippetType = switch (targetNode.kind()) {
                case METHOD -> EnrichmentSnippet.SnippetType.METHOD_CALLERS;
                case FIELD  -> EnrichmentSnippet.SnippetType.FIELD_USAGES;
                default     -> EnrichmentSnippet.SnippetType.VARIABLE_USAGES;
            };

            String usageSummary = usageEdges.stream()
                    .limit(10)
                    .map(e -> e.filePath() + ":" + e.startLine())
                    .distinct()
                    .collect(Collectors.joining(", "));

            snippets.add(new EnrichmentSnippet(
                    snippetType,
                    targetNode.filePath(), targetNode.startLine(), targetNode.endLine(),
                    targetNode.simpleName(),
                    null,
                    "'" + targetNode.simpleName() + "' is deleted but still referenced at: " + usageSummary
            ));
        }

        log.debug("DeletionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return snippets;
    }
}
