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
import java.util.stream.Collectors;

/**
 * Collects context for DELETE lines in a {@link UnionLine}.
 *
 * <p>Strategy passes:
 * <ul>
 *   <li><b>Pass 0</b>: For CLASS/INTERFACE/ANNOTATION nodes that are themselves in the union,
 *       emits a {@link EnrichmentSnippet.SnippetType#CLASS_BODY} snippet with the full class body
 *       from {@code targetGraph} <em>only when the union contains no member nodes</em>
 *       (METHOD/FIELD/CONSTRUCTOR). When member nodes are present their individual snippets
 *       provide sufficient context.</li>
 *   <li><b>Pass 1</b>: Adds a {@link EnrichmentSnippet.SnippetType#CLASS_DECLARATION} snippet
 *       for the declaring class of each deleted method/field node.</li>
 *   <li><b>Pass 2</b>: For each deleted METHOD/FIELD/CONSTRUCTOR node finds its callers/readers
 *       in {@code targetGraph} and emits caller-window snippets.
 *       CLASS/INTERFACE/ANNOTATION nodes are intentionally skipped — their incoming edges
 *       represent structural containment rather than meaningful external callers.</li>
 *   <li><b>Pass 3</b>: For ANNOTATION nodes follows incoming ANNOTATED_WITH edges to surface
 *       the element annotated on the exact changed line
 *       (matched by edge.startLine and caller.filePath).</li>
 *   <li><b>Pass 4</b>: For METHOD nodes surfaces sibling overloads (same simpleName, different id)
 *       and the interface/abstract method overridden via OVERRIDES edge.</li>
 * </ul>
 *
 * <p>Snippet budget is applied <em>per GraphNode</em> (see {@code maxSnippetsPerNode}).
 * For {@code METHOD_CALLERS}/{@code FIELD_USAGES} snippets only the call-site window
 * (±{@code app.enrichment.callerWindowLines} lines) is included.
 */
@Slf4j
@Component
public class DeletionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerNode:3}")
    private int maxSnippetsPerNode;

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
        Map<String, Integer> snippetsPerNode = new HashMap<>();

        // Pass 0: CLASS/INTERFACE/ANNOTATION nodes — emit full body ONLY when
        // the union contains no member nodes (method/field/constructor).
        // When member nodes are present their individual snippets provide sufficient context.
        boolean hasMemberNodes = union.graphNodes().stream().anyMatch(n ->
                n.kind() == NodeKind.METHOD
                || n.kind() == NodeKind.FIELD
                || n.kind() == NodeKind.CONSTRUCTOR);

        if (!hasMemberNodes) {
            for (GraphNode node : union.graphNodes()) {
                if (node.kind() != NodeKind.CLASS && node.kind() != NodeKind.INTERFACE
                        && node.kind() != NodeKind.ANNOTATION) continue;

                List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
                boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
                if (!hasDel) continue;

                GraphNode resolved = targetGraph.nodes.get(node.id());
                if (resolved == null) continue;
                if (!seenClass.add("BODY:" + resolved.id())) continue;

                snippets.add(EnrichmentSnippet.ofBody(
                        EnrichmentSnippet.SnippetType.CLASS_BODY, resolved,
                        "Full body of deleted " + resolved.kind().name().toLowerCase()
                                + " '" + resolved.simpleName() + "'",
                        EnrichmentSnippet.LineContext.DELETE));
                snippetsPerNode.merge(node.id(), 1, Integer::sum);
            }
        }

        // Pass 1: declaring classes for deleted method/field/constructor nodes
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.FIELD
                    && node.kind() != NodeKind.CONSTRUCTOR) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) continue;

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
                    .ifPresent(cls -> {
                        snippets.add(EnrichmentSnippet.ofDeclaration(
                                EnrichmentSnippet.SnippetType.CLASS_DECLARATION, cls,
                                "Declaring class of deleted method/field '" + node.simpleName() + "'",
                                EnrichmentSnippet.LineContext.DELETE));
                        snippetsPerNode.merge(node.id(), 1, Integer::sum);
                    });
        }

        // Pass 2: callers/readers of deleted METHOD/FIELD/CONSTRUCTOR nodes.
        // CLASS/INTERFACE/ANNOTATION nodes are skipped — their incoming edges
        // are structural (REFERENCES_TYPE, INSTANTIATES from own members) and
        // do not represent meaningful external callers.
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() == NodeKind.CLASS || node.kind() == NodeKind.INTERFACE
                    || node.kind() == NodeKind.ANNOTATION) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            GraphNode targetNode = targetGraph.nodes.get(node.id());
            if (targetNode == null) continue;

            List<GraphEdge> usageEdges = targetGraph.incoming(targetNode.id()).stream()
                    .filter(e -> e.kind() != EdgeKind.DECLARES)
                    .toList();
            if (usageEdges.isEmpty()) continue;

            EnrichmentSnippet.SnippetType snippetType = targetNode.kind() == NodeKind.FIELD
                    ? EnrichmentSnippet.SnippetType.FIELD_USAGES
                    : EnrichmentSnippet.SnippetType.METHOD_CALLERS;

            int callerCount = 0;
            for (GraphEdge edge : usageEdges) {
                if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) break;
                if (callerCount >= maxCallersPerNode) break;

                GraphNode caller = targetGraph.nodes.get(edge.caller());
                if (caller == null || caller.kind() != NodeKind.METHOD) continue;

                if (!seenCaller.add(caller.id())) continue;

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
                snippetsPerNode.merge(node.id(), 1, Integer::sum);
                callerCount++;
            }
        }

        // Pass 3: for ANNOTATION nodes — find the specific element annotated on the changed line.
        // We match ANNOTATED_WITH edges by edge.startLine() == changed line number AND
        // caller.filePath() == changed line filePath to avoid surfacing all usages of
        // a widely-used annotation (e.g. @Execution, @Test) across the file.
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.ANNOTATION) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            GraphNode targetNode = targetGraph.nodes.get(node.id());
            if (targetNode == null) continue;

            // Collect (filePath, lineNumber) pairs for DELETE origins of this annotation node
            Set<String> delLineKeys = origins.stream()
                    .filter(l -> l.type() == ChangedLine.LineType.DELETE)
                    .map(l -> l.filePath() + ":" + (l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber()))
                    .collect(Collectors.toSet());

            targetGraph.incoming(targetNode.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.ANNOTATED_WITH)
                    .filter(e -> {
                        GraphNode caller = targetGraph.nodes.get(e.caller());
                        if (caller == null) return false;
                        String key = caller.filePath() + ":" + e.startLine();
                        return delLineKeys.contains(key);
                    })
                    .map(e -> targetGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .filter(owner -> seenClass.add("ANNOTATED:" + owner.id()))
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
                                EnrichmentSnippet.LineContext.DELETE));
                        snippetsPerNode.merge(node.id(), 1, Integer::sum);
                    });
        }

        // Pass 4: for METHOD nodes — sibling overloads and overridden interface/abstract method
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.METHOD) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasDel = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.DELETE);
            if (!hasDel) continue;

            GraphNode targetNode = targetGraph.nodes.get(node.id());
            if (targetNode == null) continue;

            // 4a: sibling overloads — same simpleName, different id, declared in same class
            targetGraph.incoming(targetNode.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.DECLARES)
                    .map(e -> targetGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(declaringClass -> {
                        targetGraph.outgoing(declaringClass.id()).stream()
                                .filter(e -> e.kind() == EdgeKind.DECLARES)
                                .map(e -> targetGraph.nodes.get(e.callee()))
                                .filter(Objects::nonNull)
                                .filter(sibling -> sibling.kind() == NodeKind.METHOD)
                                .filter(sibling -> !sibling.id().equals(targetNode.id()))
                                .filter(sibling -> sibling.simpleName().equals(targetNode.simpleName()))
                                .filter(sibling -> seenClass.add("OVERLOAD:" + sibling.id()))
                                .forEach(sibling -> {
                                    if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) return;
                                    snippets.add(EnrichmentSnippet.ofDeclaration(
                                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, sibling,
                                            "Sibling overload of '" + targetNode.simpleName() + "'",
                                            EnrichmentSnippet.LineContext.DELETE));
                                    snippetsPerNode.merge(node.id(), 1, Integer::sum);
                                });
                    });

            if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) continue;

            // 4b: interface/abstract method overridden by this method
            targetGraph.outgoing(targetNode.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.OVERRIDES)
                    .map(e -> targetGraph.nodes.get(e.callee()))
                    .filter(Objects::nonNull)
                    .filter(iface -> seenClass.add("OVERRIDES:" + iface.id()))
                    .findFirst()
                    .ifPresent(iface -> {
                        snippets.add(EnrichmentSnippet.ofDeclaration(
                                EnrichmentSnippet.SnippetType.METHOD_DECLARATION, iface,
                                "Interface/abstract method overridden by '" + targetNode.simpleName() + "'",
                                EnrichmentSnippet.LineContext.DELETE));
                        snippetsPerNode.merge(node.id(), 1, Integer::sum);
                    });
        }

        log.debug("DeletionContextStrategy: union={} nodes={} snippets={}",
                union.id(), union.graphNodes().size(), snippets.size());
        return snippets;
    }
}
