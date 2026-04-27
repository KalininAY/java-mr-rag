package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Collects context for DELETE lines in a {@link UnionLine}.
 *
 * <p>For each deleted node finds its callers/readers in {@code targetGraph}
 * and emits one {@link EnrichmentSnippet} per unique caller with
 * {@link EnrichmentSnippet.LineContext#DELETE}.
 *
 * <p>Deduplication is performed globally across all nodes in the union:
 * a caller is emitted at most once even if it references multiple deleted symbols.
 */
@Slf4j
@Component
public class DeletionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxCallersPerNode:5}")
    private int maxCallersPerNode;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        if (union.graphNodes().isEmpty()) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();
        // global dedup across all deleted nodes in this union
        Set<String> seenCaller = new HashSet<>();

        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            GraphNode targetNode = targetGraph.nodes.get(node.id());
            if (targetNode == null) continue;

            List<GraphEdge> usageEdges = targetGraph.incoming(targetNode.id()).stream()
                    .filter(e -> e.kind() != EdgeKind.DECLARES)
                    .toList();
            if (usageEdges.isEmpty()) continue;

            for (GraphEdge edge : usageEdges) {
                if (snippets.size() >= maxSnippetsPerGroup) break;

                GraphNode caller = targetGraph.nodes.get(edge.caller());
                if (caller == null || caller.kind() != NodeKind.METHOD) continue;

                // deduplicate: same caller may appear for multiple deleted nodes
                if (!seenCaller.add(caller.id())) continue;

                EnrichmentSnippet.SnippetType snippetType = targetNode.kind() == NodeKind.FIELD
                        ? EnrichmentSnippet.SnippetType.FIELD_USAGES
                        : EnrichmentSnippet.SnippetType.METHOD_CALLERS;

                snippets.add(new EnrichmentSnippet(
                        snippetType,
                        caller.filePath(), caller.startLine(), caller.endLine(),
                        caller.simpleName(),
                        caller.sourceSnippet(),
                        "'" + caller.simpleName() + "' calls deleted '" + targetNode.simpleName() + "'",
                        EnrichmentSnippet.LineContext.DELETE
                ));
            }
        }

        log.debug("DeletionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return snippets;
    }
}
