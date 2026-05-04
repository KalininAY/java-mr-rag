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
 * Collects context for ADD lines in a {@link UnionLine}.
 *
 * <p>All emitted snippets carry {@link EnrichmentSnippet.LineContext#ADD}.
 *
 * <p>In addition to outgoing INVOKES/READS_FIELD/WRITES_FIELD edges, this strategy also:
 * <ul>
 *   <li>Adds a {@link EnrichmentSnippet.SnippetType#CLASS_DECLARATION} snippet for the
 *       declaring class of every method/field node in the union (via incoming DECLARES edges).</li>
 *   <li>Follows outgoing EXTENDS edges to surface parent class declarations.</li>
 *   <li>Follows outgoing IMPLEMENTS edges to surface implemented interface declarations.</li>
 *   <li>Follows outgoing INSTANTIATES / INSTANTIATES_ANONYMOUS edges to surface constructor
 *       declarations. Note: the CLASS_DECLARATION deduplication in
 *       {@link ContextStrategy#filterAlreadyInDiff} will drop the METHOD_DECLARATION produced
 *       here when a CLASS_DECLARATION for the same location was already collected in Pass 1,
 *       so there is no redundancy for classes that appear both as DECLARES targets and
 *       INSTANTIATES targets.</li>
 * </ul>
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

        // Pass 1: declaring classes for every method/field/constructor node in the union
        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.FIELD
                    && node.kind() != NodeKind.CONSTRUCTOR) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            sourceGraph.incoming(node.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.DECLARES)
                    .map(e -> sourceGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .filter(cls -> cls.kind() == NodeKind.CLASS
                            || cls.kind() == NodeKind.INTERFACE
                            || cls.kind() == NodeKind.ANNOTATION)
                    .filter(cls -> seenDecl.add("CLASS:" + cls.id()))
                    .findFirst()
                    .ifPresent(cls -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.CLASS_DECLARATION, cls,
                            "Declaring class of changed method/field '" + node.simpleName() + "'",
                            EnrichmentSnippet.LineContext.ADD)));
        }

        // Pass 2: outgoing edges from union nodes
        for (GraphNode node : union.graphNodes()) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            Set<Integer> addLines = origins.stream()
                    .filter(l -> l.type() == ChangedLine.LineType.ADD)
                    .map(l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber())
                    .collect(Collectors.toSet());

            for (GraphEdge edge : sourceGraph.outgoing(node.id())) {
                if (snippets.size() >= maxSnippetsPerGroup) break;

                if (edge.startLine() > 0 && !addLines.contains(edge.startLine())) continue;

                String targetId = edge.callee();
                if (!seenDecl.add(targetId)) continue;

                GraphNode target = sourceGraph.nodes.get(targetId);
                if (target == null) continue;

                switch (edge.kind()) {
                    case INVOKES -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, target,
                            "Declaration of method '" + target.simpleName() + "' called in added code",
                            EnrichmentSnippet.LineContext.ADD));
                    case INSTANTIATES, INSTANTIATES_ANONYMOUS -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, target,
                            "Constructor of '" + target.simpleName() + "' instantiated in added code",
                            EnrichmentSnippet.LineContext.ADD));
                    case READS_FIELD, WRITES_FIELD -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.FIELD_DECLARATION, target,
                            "Declaration of field '" + target.simpleName() + "' accessed in added code",
                            EnrichmentSnippet.LineContext.ADD));
                    case READS_LOCAL_VAR, WRITES_LOCAL_VAR -> snippets.add(EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION, target,
                            "Declaration of variable '" + target.simpleName() + "' used in added code",
                            EnrichmentSnippet.LineContext.ADD));
                    case EXTENDS -> {
                        if (target.kind() == NodeKind.CLASS || target.kind() == NodeKind.INTERFACE) {
                            snippets.add(EnrichmentSnippet.ofDeclaration(
                                    EnrichmentSnippet.SnippetType.CLASS_DECLARATION, target,
                                    "Parent class '" + target.simpleName() + "' extended by changed class",
                                    EnrichmentSnippet.LineContext.ADD));
                        }
                    }
                    case IMPLEMENTS -> {
                        if (target.kind() == NodeKind.INTERFACE) {
                            snippets.add(EnrichmentSnippet.ofDeclaration(
                                    EnrichmentSnippet.SnippetType.CLASS_DECLARATION, target,
                                    "Interface '" + target.simpleName() + "' implemented by changed class",
                                    EnrichmentSnippet.LineContext.ADD));
                        }
                    }
                    default -> { }
                }
            }
        }

        log.debug("AdditionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return filterAlreadyInDiff(union, snippets);
    }
}
