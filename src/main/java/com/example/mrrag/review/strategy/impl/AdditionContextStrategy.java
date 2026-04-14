package com.example.mrrag.review.strategy.impl;

import com.example.mrrag.graph.AstGraphUtils;
import com.example.mrrag.graph.model.EdgeKind;
import com.example.mrrag.graph.model.GraphEdge;
import com.example.mrrag.graph.model.GraphNode;
import com.example.mrrag.graph.model.ProjectGraph;
import com.example.mrrag.review.model.*;
import com.example.mrrag.review.strategy.ContextStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Strategy for {@link ChangeType#ADDITION}.
 *
 * <p>Focuses on newly called declarations — follows outgoing edges from
 * ADD lines to collect method/field/variable declarations that provide
 * context about what the new code is calling.
 */
@Slf4j
@Component
public class AdditionContextStrategy implements ContextStrategy {

    @Value("${app.enrichment.maxSnippetsPerGroup:12}")
    private int maxSnippetsPerGroup;

    @Value("${app.enrichment.maxSnippetLines:30}")
    private int maxSnippetLines;

    @Override
    public boolean supports(ChangeType changeType) {
        return changeType == ChangeType.ADDITION;
    }

    @Override
    public List<EnrichmentSnippet> collectContext(ChangeGroup group, ProjectGraph sourceGraph, ProjectGraph targetGraph) {
        List<EnrichmentSnippet> snippets = new ArrayList<>();
        Set<String> seenDecl = new HashSet<>();

        List<ChangedLine> addLines = group.changedLines().stream()
                .filter(l -> l.type() == ChangedLine.LineType.ADD)
                .toList();

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
                    if (!file.equals(edge.filePath()) || edge.line() != line) continue;

                    String targetId = edge.callee();
                    if (seenDecl.contains(targetId)) continue;
                    seenDecl.add(targetId);

                    GraphNode target = sourceGraph.nodes.get(targetId);
                    if (target == null) continue;

                    switch (edge.kind()) {
                        case INVOKES -> emitDeclaration(target, snippets,
                                EnrichmentSnippet.SnippetType.METHOD_DECLARATION,
                                "Declaration of method '" + target.simpleName() + "' called in added code");
                        case READS_FIELD, WRITES_FIELD -> emitDeclaration(target, snippets,
                                EnrichmentSnippet.SnippetType.FIELD_DECLARATION,
                                "Declaration of field '" + target.simpleName() + "' accessed in added code");
                        case READS_LOCAL_VAR, WRITES_LOCAL_VAR -> emitDeclaration(target, snippets,
                                EnrichmentSnippet.SnippetType.VARIABLE_DECLARATION,
                                "Declaration of variable '" + target.simpleName() + "' used in added code");
                        default -> {
                        }
                    }
                }
            }
        }

        log.debug("AdditionContextStrategy: group={} snippets={}", group.id(), snippets.size());
        return snippets;
    }

    private void emitDeclaration(
            GraphNode node,
            List<EnrichmentSnippet> snippets,
            EnrichmentSnippet.SnippetType type,
            String explanation
    ) {
        List<String> lines = node.sourceSnippet().lines().toList();
        if (lines.isEmpty()) return;
        snippets.add(new EnrichmentSnippet(
                type,
                node.filePath(), node.startLine(), node.endLine(),
                node.simpleName(), lines, explanation
        ));
    }

}
