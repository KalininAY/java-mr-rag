package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Collects context for ADD lines in a {@link UnionLine}.
 *
 * <p>For each ADD line, follows outgoing edges from its enclosing AST nodes
 * to collect method/field/variable declarations that provide context about
 * what the new code is calling.
 *
 * <p>Uses {@link UnionLine#nodeOrigins()} to determine the correct graph
 * (source for ADD nodes) when resolving each node's outgoing edges.
 */
@Slf4j
@Component
public class AdditionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

    @Override
    public List<EnrichmentSnippet> collectContext(UnionLine union, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        List<EnrichmentSnippet> snippets = new ArrayList<>();
        Set<String> seenDecl = new HashSet<>();

        List<ChangedLine> addLines = union.changedLines().stream()
                .filter(l -> l.type() == ChangedLine.LineType.ADD)
                .toList();

        if (addLines.isEmpty()) return snippets;

        for (ChangedLine cl : addLines) {
            if (snippets.size() >= maxSnippetsPerGroup) break;

            String file = AstGraphUtils.normalizeFilePath(cl.filePath(), sourceGraph);
            int line = cl.lineNumber() > 0 ? cl.lineNumber() : cl.oldLineNumber();
            if (line <= 0) continue;

            List<GraphNode> enclosing = sourceGraph.nodesAtLine(file, line);
            for (GraphNode enc : enclosing) {
                if (snippets.size() >= maxSnippetsPerGroup) break;
                for (GraphEdge edge : sourceGraph.outgoing(enc.id())) {
                    if (snippets.size() >= maxSnippetsPerGroup) break;
                    if (!file.equals(edge.filePath()) || edge.startLine() != line) continue;

                    String targetId = edge.callee();
                    if (seenDecl.contains(targetId)) continue;
                    seenDecl.add(targetId);

                    GraphNode target = sourceGraph.nodes.get(targetId);
                    if (target == null) continue;

                    switch (edge.kind()) {
                        case INVOKES -> snippets.add(new EnrichmentSnippet(
                                EnrichmentSnippet.SnippetType.METHOD_DECLARATION,
                                target,
                                "Declaration of method '" + target.simpleName() + "' called in added code"));
                        case READS_FIELD, WRITES_FIELD -> snippets.add(new EnrichmentSnippet(
                                EnrichmentSnippet.SnippetType.FIELD_DECLARATION,
                                target,
                                "Declaration of field '" + target.simpleName() + "' accessed in added code"));
                        case READS_LOCAL_VAR, WRITES_LOCAL_VAR -> snippets.add(new EnrichmentSnippet(
                                EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION,
                                target,
                                "Declaration of variable '" + target.simpleName() + "' used in added code"));
                        default -> {
                        }
                    }
                }
            }
        }

        log.debug("AdditionContextStrategy: union={} snippets={}", union.id(), snippets.size());
        return filterAlreadyInDiff(union, snippets);
    }
}
