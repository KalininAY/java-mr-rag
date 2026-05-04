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
 * Collects context for DELETE lines in a {@link UnionLine}.
 *
 * <p>For each deleted node finds its callers/readers in {@code targetGraph}
 * and emits one {@link EnrichmentSnippet} per unique caller with
 * {@link EnrichmentSnippet.LineContext#DELETE}.
 *
 * <p>Also adds a {@link EnrichmentSnippet.SnippetType#CLASS_DECLARATION} snippet
 * for the declaring class of each deleted method/field node so the LLM understands
 * the class context of the deletion.
 *
 * <p>For {@code METHOD_CALLERS} / {@code FIELD_USAGES} snippets only the
 * call-site window (±{@code app.enrichment.callerWindowLines} lines) is
 * included — not the entire caller method body.
 */
@Slf4j
@Component
public class DeletionContextStrategy implements ContextStrategy {

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
        Set<String> seenCaller = new HashSet<>();
        Set<String> seenClass = new HashSet<>();

        // Pass 1: declaring classes for deleted method/field/constructor nodes
        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.FIELD
                    && node.kind() != NodeKind.CONSTRUCTOR) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            GraphNode targetNode = targetGraph.nodes.get(node.id());
            if (targetNode == null) continue;

            targetGraph.incoming(targetNode.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.DECLARES)
                    .map(e -> targetGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .filter(cls -> cls.kind() == NodeKind.CLASS
                            || cls.kind() == NodeKind.INTERFACE
                            || cls.kind() == NodeKind.ANNOTATION)
                    .filter(cls -> seenClass.add("CLASS:" + cls.id()))
                    .findFirst()
                    .ifPresent(cls -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.CLASS_DECLARATION, cls,
                            "Declaring class of deleted method/field '" + node.simpleName() + "'",
                            EnrichmentSnippet.LineContext.DELETE)));
        }

        // Pass 2: callers/readers of deleted nodes
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

                if (!seenCaller.add(caller.id())) continue;

                EnrichmentSnippet.SnippetType snippetType = targetNode.kind() == NodeKind.FIELD
                        ? EnrichmentSnippet.SnippetType.FIELD_USAGES
                        : EnrichmentSnippet.SnippetType.METHOD_CALLERS;

                String window   = CallerSnippetExtractor.extract(caller, edge.startLine(), callerWindowLines);
                int    winStart = CallerSnippetExtractor.windowStartLine(caller, edge.startLine(), callerWindowLines);
                int    winEnd   = CallerSnippetExtractor.windowEndLine(caller, edge.startLine(), callerWindowLines);

                snippets.add(new EnrichmentSnippet(
                        snippetType,
                        caller.filePath(), winStart, winEnd,
                        caller.simpleName(),
                        window,
                        "'" + caller.simpleName() + "' calls deleted '" + targetNode.simpleName() + "'",
                        EnrichmentSnippet.LineContext.DELETE
                ));
            }
        }

        log.debug("DeletionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return snippets;
    }
}
