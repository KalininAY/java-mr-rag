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
 *   <li><b>Pass 0</b>: For CLASS/INTERFACE/ANNOTATION nodes that are themselves in the union,
 *       emits a {@link EnrichmentSnippet.SnippetType#CLASS_BODY} snippet with the full class body
 *       <em>only when the union contains no member nodes</em> (METHOD/FIELD/CONSTRUCTOR).
 *       When member nodes are present, their individual snippets provide sufficient context.</li>
 *   <li><b>Pass 1</b>: Adds a {@link EnrichmentSnippet.SnippetType#CLASS_DECLARATION} snippet
 *       for the declaring class of every method/field node in the union (via DECLARES edges).</li>
 *   <li><b>Pass 2</b>: Follows outgoing INVOKES/READS_FIELD/WRITES_FIELD/EXTENDS/IMPLEMENTS/
 *       INSTANTIATES edges to surface declarations used by added code.</li>
 *   <li><b>Pass 3</b>: For ANNOTATION nodes follows incoming ANNOTATED_WITH edges to surface
 *       the method/field/class that the annotation decorates on the exact changed line
 *       (matched by edge.startLine and caller.filePath).</li>
 *   <li><b>Pass 4</b>: For METHOD nodes surfaces sibling overloads (same simpleName, different id)
 *       and the interface/abstract method overridden via OVERRIDES edge.</li>
 *   <li><b>Pass 5</b>: For METHOD/CONSTRUCTOR nodes whose signature line is among the changed
 *       ADD lines (changed line &le; node.startLine + 2), emits a
 *       {@link EnrichmentSnippet.SnippetType#METHOD_BODY} snippet of the node itself so that
 *       the full method body appears in the "Enclosing context" section.</li>
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

        // Pass 3: for ANNOTATION nodes — find the specific element annotated on the changed line.
        // We match ANNOTATED_WITH edges by edge.startLine() == changed line number AND
        // caller.filePath() == changed line filePath to avoid surfacing all usages of
        // a widely-used annotation (e.g. @Execution, @Test) across the file.
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.ANNOTATION) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            // Collect (filePath, lineNumber) pairs for ADD origins of this annotation node
            Set<String> addLineKeys = origins.stream()
                    .filter(l -> l.type() == ChangedLine.LineType.ADD)
                    .map(l -> l.filePath() + ":" + (l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber()))
                    .collect(Collectors.toSet());

            sourceGraph.incoming(node.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.ANNOTATED_WITH)
                    .filter(e -> {
                        GraphNode caller = sourceGraph.nodes.get(e.caller());
                        if (caller == null) return false;
                        // edge.startLine() is the line where @Annotation appears on the caller
                        String key = caller.filePath() + ":" + e.startLine();
                        return addLineKeys.contains(key);
                    })
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

        // Pass 4: for METHOD nodes — sibling overloads and overridden interface/abstract method
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.METHOD) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean hasAdd = origins.stream().anyMatch(l -> l.type() == ChangedLine.LineType.ADD);
            if (!hasAdd) continue;

            // 4a: sibling overloads — same simpleName, different id, declared in same class
            sourceGraph.incoming(node.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.DECLARES)
                    .map(e -> sourceGraph.nodes.get(e.caller()))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(declaringClass -> {
                        sourceGraph.outgoing(declaringClass.id()).stream()
                                .filter(e -> e.kind() == EdgeKind.DECLARES)
                                .map(e -> sourceGraph.nodes.get(e.callee()))
                                .filter(Objects::nonNull)
                                .filter(sibling -> sibling.kind() == NodeKind.METHOD)
                                .filter(sibling -> !sibling.id().equals(node.id()))
                                .filter(sibling -> sibling.simpleName().equals(node.simpleName()))
                                .filter(sibling -> seenDecl.add("OVERLOAD:" + sibling.id()))
                                .forEach(sibling -> {
                                    if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) return;
                                    snippets.add(EnrichmentSnippet.ofDeclaration(
                                            EnrichmentSnippet.SnippetType.METHOD_DECLARATION, sibling,
                                            "Sibling overload of '" + node.simpleName() + "'",
                                            EnrichmentSnippet.LineContext.ADD));
                                    snippetsPerNode.merge(node.id(), 1, Integer::sum);
                                });
                    });

            if (snippetsPerNode.getOrDefault(node.id(), 0) >= maxSnippetsPerNode) continue;

            // 4b: interface/abstract method overridden by this method
            sourceGraph.outgoing(node.id()).stream()
                    .filter(e -> e.kind() == EdgeKind.OVERRIDES)
                    .map(e -> sourceGraph.nodes.get(e.callee()))
                    .filter(Objects::nonNull)
                    .filter(iface -> seenDecl.add("OVERRIDES:" + iface.id()))
                    .findFirst()
                    .ifPresent(iface -> {
                        snippets.add(EnrichmentSnippet.ofDeclaration(
                                EnrichmentSnippet.SnippetType.METHOD_DECLARATION, iface,
                                "Interface/abstract method overridden by '" + node.simpleName() + "'",
                                EnrichmentSnippet.LineContext.ADD));
                        snippetsPerNode.merge(node.id(), 1, Integer::sum);
                    });
        }

        // Pass 5: for METHOD/CONSTRUCTOR nodes — if a changed ADD line touches the signature
        // area (changed line <= node.startLine + 2), emit a METHOD_BODY snippet of the node
        // itself so that the full body appears in the "Enclosing context" section.
        for (GraphNode node : union.graphNodes()) {
            if (node.kind() != NodeKind.METHOD && node.kind() != NodeKind.CONSTRUCTOR) continue;

            List<ChangedLine> origins = union.nodeOrigins().getOrDefault(node, List.of());
            boolean signatureChanged = origins.stream()
                    .filter(l -> l.type() == ChangedLine.LineType.ADD)
                    .map(l -> l.lineNumber() > 0 ? l.lineNumber() : l.oldLineNumber())
                    .anyMatch(ln -> ln <= node.startLine() + 2);
            if (!signatureChanged) continue;

            GraphNode resolved = sourceGraph.nodes.get(node.id());
            if (resolved == null || resolved.sourceSnippet() == null) continue;
            if (!seenDecl.add("BODY:" + resolved.id())) continue;

            snippets.add(EnrichmentSnippet.ofBody(
                    EnrichmentSnippet.SnippetType.METHOD_BODY, resolved,
                    "Body of " + resolved.kind().name().toLowerCase() + " '" + resolved.simpleName()
                            + "' whose signature was changed",
                    EnrichmentSnippet.LineContext.ADD));
            snippetsPerNode.merge(node.id(), 1, Integer::sum);
        }

        log.debug("AdditionContextStrategy: union={} nodes={} snippets={}",
                union.id(), union.graphNodes().size(), snippets.size());
        return snippets;
    }
}
