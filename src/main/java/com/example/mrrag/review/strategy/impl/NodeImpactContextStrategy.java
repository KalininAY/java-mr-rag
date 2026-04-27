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
import java.util.stream.Collectors;

/**
 * Impact-analysis strategy: for every AST node in {@link UnionLine#graphNodes()},
 * follows <em>incoming</em> edges to surface callers and field-readers that are
 * affected by the change.
 *
 * <p>Uses {@link UnionLine#nodeOrigins()} to pick the correct graph per node:
 * <ul>
 *   <li><b>ADD-only</b> node — incoming edges in {@code sourceGraph}:
 *       who already calls/reads the new symbol in the feature branch.</li>
 *   <li><b>DELETE-only</b> node — incoming edges in {@code targetGraph}:
 *       who still depends on the removed symbol (impact of deletion).</li>
 *   <li><b>ADD + DELETE</b> node (modified in place) — incoming from both graphs
 *       so the reviewer sees callers before and after.</li>
 * </ul>
 *
 * <p>Only {@link NodeKind#METHOD} and {@link NodeKind#FIELD} nodes are processed;
 * local variables produce too much noise.
 */
@Slf4j
@Component
public class NodeImpactContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxCallersPerNode:5}")
    private int maxCallersPerNode;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        if (union.graphNodes().isEmpty()) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();
        Set<String> seenKey = new HashSet<>(); // nodeId#graph to avoid dups

        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.FIELD) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);

            if (hasAdd) {
                collectIncoming(node, sourceGraph, "source", seenKey, snippets, union);
            }
            if (hasDel) {
                // look up same node in targetGraph by id
                GraphNode targetNode = targetGraph.nodes.get(node.id());
                if (targetNode != null) {
                    collectIncoming(targetNode, targetGraph, "target", seenKey, snippets, union);
                }
            }
        }

        log.debug("NodeImpactContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return snippets;
    }

    private void collectIncoming(
            GraphNode node,
            ProjectGraph graph,
            String graphLabel,
            Set<String> seenKey,
            List<EnrichmentSnippet> snippets,
            UnionLine union) {

        String key = node.id() + "#" + graphLabel;
        if (!seenKey.add(key)) return;
        if (snippets.size() >= maxSnippetsPerGroup) return;

        List<GraphEdge> incoming = graph.incoming(node.id()).stream()
                .filter(e -> e.kind() == EdgeKind.INVOKES
                        || e.kind() == EdgeKind.READS_FIELD
                        || e.kind() == EdgeKind.WRITES_FIELD)
                .toList();

        if (incoming.isEmpty()) return;

        // Collect caller nodes (METHOD that contains the edge)
        List<GraphNode> callers = incoming.stream()
                .limit(maxCallersPerNode)
                .map(e -> graph.nodes.get(e.caller()))
                .filter(Objects::nonNull)
                .filter(c -> c.kind() == NodeKind.METHOD)
                .distinct()
                .collect(Collectors.toList());

        if (callers.isEmpty()) return;

        EnrichmentSnippet.SnippetType snippetType = node.kind() == NodeKind.FIELD
                ? EnrichmentSnippet.SnippetType.FIELD_USAGES
                : EnrichmentSnippet.SnippetType.METHOD_CALLERS;

        String callerList = callers.stream()
                .map(c -> c.simpleName() + " (" + c.filePath() + ":" + c.startLine() + ")")
                .collect(Collectors.joining(", "));

        String suffix = hasDel(union, node) && hasAdd(union, node)
                ? " [" + graphLabel + " branch]"
                : "";

        snippets.add(new EnrichmentSnippet(
                snippetType,
                node.filePath(), node.startLine(), node.endLine(),
                node.simpleName(),
                null,
                callers.size() + " caller(s) of '" + node.simpleName() + "'" + suffix + ": " + callerList
        ));
    }

    private static boolean hasAdd(UnionLine union, GraphNode node) {
        return union.nodeOrigins().getOrDefault(node, List.of()).stream()
                .anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
    }

    private static boolean hasDel(UnionLine union, GraphNode node) {
        return union.nodeOrigins().getOrDefault(node, List.of()).stream()
                .anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
    }
}
