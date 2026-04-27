package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.NodeKind;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.CallerSnippetExtractor;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Impact-analysis strategy: for every AST node in {@link UnionLine#graphNodes()},
 * follows <em>incoming</em> edges to surface callers and field-readers that are
 * affected by the change.
 *
 * <p>For {@code METHOD_CALLERS} / {@code FIELD_USAGES} snippets only the
 * call-site window (±{@code app.enrichment.callerWindowLines} lines) is
 * included — not the entire caller method body.
 */
@Slf4j
@Component
public class NodeImpactContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxCallersPerNode:5}")
    private int maxCallersPerNode;

    @Value("${app.enrichment.callerWindowLines:5}")
    private int callerWindowLines;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        if (union.graphNodes().isEmpty()) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();
        Set<String> seenKey = new HashSet<>();

        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.FIELD) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);

            if (hasAdd) {
                GraphNode sourceNode = sourceGraph.nodes.get(node.id());
                if (sourceNode != null) {
                    EnrichmentSnippet.LineContext ctx = hasDel
                            ? EnrichmentSnippet.LineContext.BOTH
                            : EnrichmentSnippet.LineContext.ADD;
                    collectCallerSnippets(sourceNode, sourceGraph, ctx, seenKey, snippets);
                }
            }
            if (hasDel) {
                GraphNode targetNode = targetGraph.nodes.get(node.id());
                if (targetNode != null) {
                    EnrichmentSnippet.LineContext ctx = hasAdd
                            ? EnrichmentSnippet.LineContext.BOTH
                            : EnrichmentSnippet.LineContext.DELETE;
                    collectCallerSnippets(targetNode, targetGraph, ctx, seenKey, snippets);
                }
            }
        }

        log.debug("NodeImpactContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return snippets;
    }

    private void collectCallerSnippets(
            GraphNode node,
            ProjectGraph graph,
            EnrichmentSnippet.LineContext lineContext,
            Set<String> seenKey,
            List<EnrichmentSnippet> snippets) {

        List<GraphEdge> incoming = graph.incoming(node.id()).stream()
                .filter(e -> e.kind() == EdgeKind.INVOKES
                        || e.kind() == EdgeKind.READS_FIELD
                        || e.kind() == EdgeKind.WRITES_FIELD)
                .toList();

        if (incoming.isEmpty()) return;

        EnrichmentSnippet.SnippetType snippetType = node.kind() == NodeKind.FIELD
                ? EnrichmentSnippet.SnippetType.FIELD_USAGES
                : EnrichmentSnippet.SnippetType.METHOD_CALLERS;

        int count = 0;
        for (GraphEdge edge : incoming) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            if (count >= maxCallersPerNode) break;

            String key = edge.caller() + "#" + lineContext;
            if (!seenKey.add(key)) continue;

            GraphNode caller = graph.nodes.get(edge.caller());
            if (caller == null || caller.kind() != NodeKind.METHOD) continue;

            String window   = CallerSnippetExtractor.extract(caller, edge.startLine(), callerWindowLines);
            int    winStart = CallerSnippetExtractor.windowStartLine(caller, edge.startLine(), callerWindowLines);
            int    winEnd   = CallerSnippetExtractor.windowEndLine(caller, edge.startLine(), callerWindowLines);

            snippets.add(new EnrichmentSnippet(
                    snippetType,
                    caller.filePath(), winStart, winEnd,
                    caller.simpleName(),
                    window,
                    "'" + caller.simpleName() + "' calls '" + node.simpleName() + "'",
                    lineContext
            ));
            count++;
        }
    }
}
