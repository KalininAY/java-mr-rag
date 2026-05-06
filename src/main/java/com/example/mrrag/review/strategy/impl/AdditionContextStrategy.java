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
 * <p>Snippet budget is applied <em>per GraphNode</em> (see {@code maxSnippetsPerNode}):
 * each node in the union independently collects up to that many snippets so that
 * all changed nodes get context regardless of how large the union is.
 *
 * <p>Strategy passes:
 * <ul>
 *   <li><b>Pass 0</b>: For CLASS/INTERFACE/ANNOTATION nodes that are themselves in the union
 *       (container-only change), emits a {@link EnrichmentSnippet.SnippetType#CLASS_BODY}
 *       snippet with the full class body.</li>
 *   <li><b>Pass 1</b>: Adds a {@link EnrichmentSnippet.SnippetType#CLASS_DECLARATION} snippet
 *       for the declaring class of every method/field node in the union (via DECLARES edges).</li>
 *   <li><b>Pass 2</b>: Follows outgoing INVOKES/READS_FIELD/WRITES_FIELD/EXTENDS/IMPLEMENTS/
 *       INSTANTIATES edges to surface declarations used by added code.</li>
 *   <li><b>Pass 3</b>: For ANNOTATION nodes follows incoming ANNOTATED_WITH edges to surface
 *       the method/field/class that the annotation decorates.</li>
 * </ul>
 */
@Slf4j
@Component
public class AdditionContextStrategy implements ContextStrategy {

    /**
     * Maximum number of enrichment snippets collected per GraphNode.
     * Each node in the union independently counts toward this limit so that
     * every changed node is guaranteed to produce at least some context.
     */
    @Value("${app.enrichment.maxSnippetsPerNode:3}")
    private int maxSnippetsPerNode;

    @Override
    public List<EnrichmentSnippet> collectContext(
            UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {

        if (union.graphNodes().isEmpty()) return List.of();

        List<EnrichmentSnippet> snippets = new ArrayList<>();
        Set<String> seenDecl = new HashSet<>();
        // per-node snippet counter: nodeId -> count emitted for that node
        Map<String, Integer> snippetsPerNode = new HashMap<>();

        // Pass 0: CLASS/INTERFACE/ANNOTATION nodes that are themselves in the union—emit full body
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.CLASS && node.kind() != NodeKind.INTERFACE
                    && node.kind() != NodeKind.ANNOTATION) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            GraphNode resolved = sourceGraph.nodes.get(node.id());
            if (resolved == null) continue;
            if (!seenDecl.add("BODY:" + resolved.id())) continue;

            snippets.add(EnrichmentSnippet.ofBody(
                    EnrichmentSnippet.SnippetType.CLASS_BODY, resolved,
                    "Full body of added " + resolved.kind().name().toLowerCase()
                            + " '" + resolved.simpleName() + "'",
                    EnrichmentSnippet.LineContext.ADD));
            snippetsPerNode.merge(node.id(), 1, Integer::sum);
        }

        // Pass 1: declaring classes for every method/field/constructor node in the union
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.FIELD
                    && node.kind() != NodeKind.CONSTRUCTOR) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) continue;

            sourceGraph.incoming(node.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.DECLARES)
                    .map(e -> sourceGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .filter(cls -> cls.kind() == NodeKind.CLASS
                            || cls.kind() == NodeKind.INTERFACE
                            || cls.kind() == NodeKind.ANNOTATION)
                    .filter(cls -> seenDecl.add("CLASS:" + cls.id()))
                    .findFirst()
                    .ifPresent(cls -> {
                        snippets.add(EnrichmentSnippet.ofDeclaration(
                                EnrichmentSnippet.SnippetType.CLASS_DECLARATION, cls,
                                "Declaring class of changed method/field '" + node.simpleName() + "'",
                                EnrichmentSnippet.LineContext.ADD));
                        snippetsPerNode.merge(node.id(), 1, Integer::sum);
                    });
        }

        // Pass 2: outgoing edges from union nodes
        for (GraphNode node : union.graphNodes()) {
            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            Set<Integer> addLines = origins.stream()
                    .filter(l -> l.type() == ChangedLine.LineType.ADD)
                    .map(l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber())
                    .collect(Collectors.toSet());

            for (GraphEdge edge : sourceGraph.outgoing(node.id())) {
                if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) break;

                if (edge.startLine() > 0 && !addLines.contains(edge.startLine())) continue;

                String targetId = edge.callee();
                if (!seenDecl.add(targetId)) continue;

                GraphNode target = sourceGraph.nodes.get(targetId);
                if (target == null) continue;

                EnrichmentSnippet snippet = switch (edge.kind()) {
                    case INVOKES -> EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, target,
                            "Declaration of method '" + target.simpleName() + "' called in added code",
                            EnrichmentSnippet.LineContext.ADD);
                    case INSTANTIATES, INSTANTIATES_ANONYMOUS -> EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, target,
                            "Constructor of '" + target.simpleName() + "' instantiated in added code",
                            EnrichmentSnippet.LineContext.ADD);
                    case READS_FIELD, WRITES_FIELD -> EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.FIELD_DECLARATION, target,
                            "Declaration of field '" + target.simpleName() + "' accessed in added code",
                            EnrichmentSnippet.LineContext.ADD);
                    case READS_LOCAL_VAR, WRITES_LOCAL_VAR -> EnrichmentSnippet.ofDeclaration(
                            EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION, target,
                            "Declaration of variable '" + target.simpleName() + "' used in added code",
                            EnrichmentSnippet.LineContext.ADD);
                    case EXTENDS -> {
                        if (target.kind() == NodeKind.CLASS || target.kind() == NodeKind.INTERFACE) {
                            yield EnrichmentSnippet.ofDeclaration(
                                    EnrichmentSnippet.SnippetType.CLASS_DECLARATION, target,
                                    "Parent class '" + target.simpleName() + "' extended by changed class",
                                    EnrichmentSnippet.LineContext.ADD);
                        }
                        yield null;
                    }
                    case IMPLEMENTS -> {
                        if (target.kind() == NodeKind.INTERFACE) {
                            yield EnrichmentSnippet.ofDeclaration(
                                    EnrichmentSnippet.SnippetType.CLASS_DECLARATION, target,
                                    "Interface '" + target.simpleName() + "' implemented by changed class",
                                    EnrichmentSnippet.LineContext.ADD);
                        }
                        yield null;
                    }
                    default -> null;
                };

                if (snippet != null) {
                    snippets.add(snippet);
                    snippetsPerNode.merge(node.id(), 1, Integer::sum);
                }
            }
        }

        // Pass 3: for ANNOTATION nodes — find elements annotated with this annotation
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.ANNOTATION) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            sourceGraph.incoming(node.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.ANNOTATED_WITH)
                    .map(e -> sourceGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .filter(owner -> seenDecl.add("ANNOTATED:" + owner.id()))
                    .limit(maxSnippetsPerNode)
                    .forEach(owner -> {
                        if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) return;
                        EnrichmentSnippet.SnippetType type = switch (owner.kind()) {
                            case CLASS, INTERFACE -> EnrichmentSnippet.SnippetType.CLASS_DECLARATION;
                            case FIELD -> EnrichmentSnippet.SnippetType.FIELD_DECLARATION;
                            default -> EnrichmentSnippet.SnippetType.METHOD_DECLARATION;
                        };
                        snippets.add(EnrichmentSnippet.ofDeclaration(
                                type, owner,
                                "Element annotated with '@" + node.simpleName() + "'",
                                EnrichmentSnippet.LineContext.ADD));
                        snippetsPerNode.merge(node.id(), 1, Integer::sum);
                    });
        }

        log.debug("AdditionContextStrategy: union={} nodes={} snippets={}",
                union.id(), union.graphNodes().size(), snippets.size());
        return filterAlreadyInDiff(union, snippets);
    }
}
